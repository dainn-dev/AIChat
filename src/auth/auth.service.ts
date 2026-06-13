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
import { Tier, TIER_LIMITS } from '../common/tiers';
import { AuthSession } from './entities/auth-session.entity';
import { UsageCounter } from './entities/usage-counter.entity';
import { User } from './entities/user.entity';
import { AccessTokenPayload } from './jwt-payload';
import {
  AuthTokensResponse,
  MeResponse,
  RefreshResponse,
  toPublicUser,
} from './dto/auth-response';

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
    @InjectRepository(UsageCounter)
    private readonly usageCounters: Repository<UsageCounter>,
    private readonly jwt: JwtService,
    config: ConfigService,
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

    const counter = await this.usageCounters.findOne({
      where: { userId: user.id, date: this.today() },
    });
    const limits = TIER_LIMITS[user.tier];

    return {
      user: toPublicUser(user),
      tier: user.tier,
      usage: {
        replies_used: counter?.repliesUsed ?? 0,
        replies_limit: limits.repliesPerDay,
        screenshots_used: counter?.screenshotsUsed ?? 0,
        screenshots_limit: limits.screenshotsPerDay,
      },
    };
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

  /** Current UTC date as `YYYY-MM-DD` (quota reset boundary, DAI-124 §5.6). */
  private today(): string {
    return new Date().toISOString().slice(0, 10);
  }
}
