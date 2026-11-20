import { createHash, randomBytes } from 'node:crypto';
import {
  ConflictException,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { InjectRepository } from '@nestjs/typeorm';
import * as bcrypt from 'bcryptjs';
import { Repository } from 'typeorm';
import { AuthConfig } from '../config/configuration';
import { Tier } from '../common/tiers';
import { UsageService } from '../usage/usage.service';
import { Tier as UsageTier } from '../usage/usage.constants';
import { AuthSession } from './entities/auth-session.entity';
import { User } from './entities/user.entity';
import { AccessTokenPayload } from './jwt-payload';
import {
  AuthTokensResponse,
  MeResponse,
  RefreshResponse,
  SettingsResponse,
  toPublicUser,
  toSettingsView,
} from './dto/auth-response';
import { UpdateSettingsDto } from './dto/update-settings.dto';
import { ConversationErasureService } from '../conversations/conversation-erasure.service';

/** Postgres unique-violation SQLSTATE. */
const PG_UNIQUE_VIOLATION = '23505';

@Injectable()
export class AuthService {
  private readonly authConfig: AuthConfig;

  constructor(
    @InjectRepository(User)
    private readonly users: Repository<User>,
    @InjectRepository(AuthSession)
    private readonly sessions: Repository<AuthSession>,
    private readonly usage: UsageService,
    private readonly jwt: JwtService,
    config: ConfigService,
    private readonly erasure: ConversationErasureService,
  ) {
    this.authConfig = config.getOrThrow<AuthConfig>('auth');
  }

  /** FR-A1, FR-A6: create a `free`-tier user and issue an initial session. */
  async signup(input: {
    email: string;
    password: string;
    displayName?: string;
  }): Promise<AuthTokensResponse> {
    const email = this.normalizeEmail(input.email);

    if (await this.users.exists({ where: { email } })) {
      throw new ConflictException('An account with this email already exists.');
    }

    const passwordHash = await bcrypt.hash(
      input.password,
      this.authConfig.bcryptRounds,
    );

    const user = this.users.create({
      email,
      passwordHash,
      displayName: input.displayName ?? null,
      tier: Tier.Free,
    });

    let saved: User;
    try {
      saved = await this.users.save(user);
    } catch (err) {
      // Guards against a race between the existence check and insert.
      if ((err as { code?: string }).code === PG_UNIQUE_VIOLATION) {
        throw new ConflictException(
          'An account with this email already exists.',
        );
      }
      throw err;
    }

    const tokens = await this.issueSession(saved);
    return { user: toPublicUser(saved), ...tokens };
  }

  /** FR-A2: verify credentials and issue a session. */
  async login(input: {
    email: string;
    password: string;
  }): Promise<AuthTokensResponse> {
    const email = this.normalizeEmail(input.email);
    const user = await this.users.findOne({ where: { email } });

    // Same error whether the email is unknown or the password is wrong, so we
    // don't leak which emails are registered.
    const ok =
      user && (await bcrypt.compare(input.password, user.passwordHash));
    if (!ok) {
      throw new UnauthorizedException('Invalid email or password.');
    }

    const tokens = await this.issueSession(user);
    return { user: toPublicUser(user), ...tokens };
  }

  /**
   * FR-A3: validate the presented refresh token, rotate it (revoke the old
   * session, mint a new one) and return a fresh access + refresh pair.
   */
  async refresh(refreshToken: string): Promise<RefreshResponse> {
    const session = await this.sessions.findOne({
      where: { refreshTokenHash: this.hashToken(refreshToken) },
    });

    if (!session || session.revokedAt || session.expiresAt <= new Date()) {
      throw new UnauthorizedException('Invalid or expired refresh token.');
    }

    const user = await this.users.findOne({ where: { id: session.userId } });
    if (!user) {
      throw new UnauthorizedException('Invalid or expired refresh token.');
    }

    // Rotate: the presented token is single-use.
    session.revokedAt = new Date();
    await this.sessions.save(session);

    return this.issueSession(user);
  }

  /** FR-A4: revoke the refresh token so it can no longer be rotated. */
  async logout(refreshToken: string): Promise<void> {
    const session = await this.sessions.findOne({
      where: { refreshTokenHash: this.hashToken(refreshToken) },
    });
    if (session && !session.revokedAt) {
      session.revokedAt = new Date();
      await this.sessions.save(session);
    }
    // Idempotent: unknown/already-revoked tokens still resolve to a 204.
  }

  /** Backs `GET /me`: current user + tier + today's usage against limits. */
  async getMe(userId: string): Promise<MeResponse> {
    const user = await this.users.findOne({ where: { id: userId } });
    if (!user) {
      throw new UnauthorizedException('User no longer exists.');
    }

    // Read today's usage from the canonical metric-based `usage_counters` via
    // UsageService — the same source the quota-enforcement path writes to, so
    // `GET /me` and enforcement never disagree (DAI-145). `Tier` enums in
    // `common` and `usage` share identical string values.
    const usage = await this.usage.getUsageSummary(
      user.id,
      user.tier as unknown as UsageTier,
    );

    return {
      user: toPublicUser(user),
      tier: user.tier,
      usage,
      settings: toSettingsView(user),
    };
  }

  /**
   * Backs `PATCH /me/settings` (Android Phase 0): toggles the memory opt-in
   * that gates keyboard conversation persistence.
   *
   * Opting OUT (stored `true` → requested `false`) also purges every
   * keyboard-captured conversation and the memories only they backed, in the
   * same transaction as the flag change (product question 4). The user row is
   * locked first (`FOR NO KEY UPDATE`), which conflicts with the `FOR SHARE`
   * re-check in keyboard capture, so no capture can land after the purge.
   * Screenshot/share data is untouched. Any other transition (true → true,
   * false → false, false → true) only writes the flag and deletes nothing;
   * `deleted` is present only when a purge ran.
   */
  async updateSettings(
    userId: string,
    dto: UpdateSettingsDto,
  ): Promise<SettingsResponse> {
    return this.users.manager.transaction(async (manager) => {
      const rows: Array<{ memory_opt_in: boolean }> = await manager.query(
        `SELECT memory_opt_in FROM users WHERE id = $1 FOR NO KEY UPDATE`,
        [userId],
      );
      if (rows.length === 0) {
        throw new UnauthorizedException('User no longer exists.');
      }
      const wasOptedIn = rows[0].memory_opt_in === true;
      await manager.query(
        `UPDATE users SET memory_opt_in = $2, updated_at = now() WHERE id = $1`,
        [userId, dto.memory_opt_in],
      );
      const settings = { memory_opt_in: dto.memory_opt_in };
      if (!wasOptedIn || dto.memory_opt_in) return { settings };

      const deleted = await this.erasure.purgeKeyboardConversations(
        manager,
        userId,
      );
      return { settings, deleted };
    });
  }

  /** Mints an access JWT and persists a hashed refresh-token session. */
  private async issueSession(
    user: User,
  ): Promise<{ access_token: string; refresh_token: string }> {
    const payload: AccessTokenPayload = {
      sub: user.id,
      email: user.email,
      tier: user.tier,
    };
    const accessToken = await this.jwt.signAsync(payload, {
      secret: this.authConfig.jwtAccessSecret,
      expiresIn: this.authConfig.accessTokenTtl,
    });

    const refreshToken = randomBytes(48).toString('base64url');
    const expiresAt = new Date();
    expiresAt.setDate(
      expiresAt.getDate() + this.authConfig.refreshTokenTtlDays,
    );

    await this.sessions.save(
      this.sessions.create({
        userId: user.id,
        refreshTokenHash: this.hashToken(refreshToken),
        expiresAt,
        revokedAt: null,
      }),
    );

    return { access_token: accessToken, refresh_token: refreshToken };
  }

  private hashToken(token: string): string {
    return createHash('sha256').update(token).digest('hex');
  }

  private normalizeEmail(email: string): string {
    return email.trim().toLowerCase();
  }
}
