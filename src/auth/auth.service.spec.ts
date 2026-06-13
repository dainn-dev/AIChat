import { ConflictException, UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import * as bcrypt from 'bcryptjs';
import { AuthService } from './auth.service';
import { AuthConfig } from '../config/configuration';
import { Tier } from '../common/tiers';
import { AuthSession } from './entities/auth-session.entity';
import { UsageCounter } from './entities/usage-counter.entity';
import { User } from './entities/user.entity';

const AUTH_CONFIG: AuthConfig = {
  jwtAccessSecret: 'test-secret',
  accessTokenTtl: '15m',
  refreshTokenTtlDays: 30,
  bcryptRounds: 4, // keep hashing fast in tests
};

/** Minimal in-memory repository double covering the methods AuthService uses. */
function makeRepo() {
  return {
    exists: jest.fn(),
    findOne: jest.fn(),
    create: jest.fn((obj) => ({ ...obj })),
    save: jest.fn(),
  };
}

describe('AuthService', () => {
  let service: AuthService;
  let users: ReturnType<typeof makeRepo>;
  let sessions: ReturnType<typeof makeRepo>;
  let usageCounters: ReturnType<typeof makeRepo>;
  let jwt: JwtService;

  const buildUser = async (over: Partial<User> = {}): Promise<User> =>
    ({
      id: 'user-1',
      email: 'a@b.com',
      passwordHash: await bcrypt.hash('password123', 4),
      displayName: null,
      tier: Tier.Free,
      createdAt: new Date('2026-01-01T00:00:00Z'),
      updatedAt: new Date('2026-01-01T00:00:00Z'),
      ...over,
    }) as User;

  beforeEach(() => {
    users = makeRepo();
    sessions = makeRepo();
    usageCounters = makeRepo();
    jwt = new JwtService({});

    const config = {
      getOrThrow: jest.fn().mockReturnValue(AUTH_CONFIG),
    } as unknown as ConfigService;

    service = new AuthService(
      users as never,
      sessions as never,
      usageCounters as never,
      jwt,
      config,
    );

    // Default: sessions.save echoes the row back with an id.
    sessions.save.mockImplementation(async (s) => ({ id: 'sess-1', ...s }));
  });

  describe('signup', () => {
    it('creates a free-tier user, hashes the password, and returns tokens', async () => {
      users.exists.mockResolvedValue(false);
      users.save.mockImplementation(async (u) => ({
        ...u,
        id: 'user-1',
        createdAt: new Date('2026-01-01T00:00:00Z'),
        updatedAt: new Date('2026-01-01T00:00:00Z'),
      }));

      const res = await service.signup({
        email: 'New@Example.com',
        password: 'password123',
        displayName: 'New User',
      });

      // Email normalized to lowercase.
      const created = users.create.mock.calls[0][0];
      expect(created.email).toBe('new@example.com');
      expect(created.tier).toBe(Tier.Free);
      // Password stored hashed, never in plaintext.
      expect(created.passwordHash).not.toBe('password123');
      expect(await bcrypt.compare('password123', created.passwordHash)).toBe(
        true,
      );

      expect(res.access_token).toEqual(expect.any(String));
      expect(res.refresh_token).toEqual(expect.any(String));
      expect(res.user.tier).toBe(Tier.Free);
      // Public user never leaks the hash.
      expect(
        (res.user as unknown as Record<string, unknown>).passwordHash,
      ).toBeUndefined();
      // A refresh session was persisted (hashed token only).
      expect(sessions.save).toHaveBeenCalledTimes(1);
      const session = sessions.save.mock.calls[0][0];
      expect(session.refreshTokenHash).not.toBe(res.refresh_token);
    });

    it('rejects a duplicate email with 409', async () => {
      users.exists.mockResolvedValue(true);
      await expect(
        service.signup({ email: 'a@b.com', password: 'password123' }),
      ).rejects.toBeInstanceOf(ConflictException);
      expect(users.save).not.toHaveBeenCalled();
    });

    it('translates a unique-violation race into 409', async () => {
      users.exists.mockResolvedValue(false);
      users.save.mockRejectedValue({ code: '23505' });
      await expect(
        service.signup({ email: 'a@b.com', password: 'password123' }),
      ).rejects.toBeInstanceOf(ConflictException);
    });
  });

  describe('login', () => {
    it('returns tokens for valid credentials', async () => {
      users.findOne.mockResolvedValue(await buildUser());
      const res = await service.login({
        email: 'A@B.com',
        password: 'password123',
      });
      expect(res.access_token).toEqual(expect.any(String));
      expect(res.refresh_token).toEqual(expect.any(String));
      expect(sessions.save).toHaveBeenCalledTimes(1);
    });

    it('rejects a wrong password with 401', async () => {
      users.findOne.mockResolvedValue(await buildUser());
      await expect(
        service.login({ email: 'a@b.com', password: 'wrong-password' }),
      ).rejects.toBeInstanceOf(UnauthorizedException);
    });

    it('rejects an unknown email with 401 (no user enumeration)', async () => {
      users.findOne.mockResolvedValue(null);
      await expect(
        service.login({ email: 'nobody@b.com', password: 'password123' }),
      ).rejects.toBeInstanceOf(UnauthorizedException);
    });
  });

  describe('refresh', () => {
    it('rotates a valid refresh token and revokes the old session', async () => {
      const existing: Partial<AuthSession> = {
        id: 'sess-old',
        userId: 'user-1',
        refreshTokenHash: 'irrelevant — matched by query',
        expiresAt: new Date(Date.now() + 86_400_000),
        revokedAt: null,
      };
      sessions.findOne.mockResolvedValue(existing);
      users.findOne.mockResolvedValue(await buildUser());

      const res = await service.refresh('some-refresh-token');

      // Old session revoked, then a new session persisted → 2 saves.
      expect(existing.revokedAt).toBeInstanceOf(Date);
      expect(sessions.save).toHaveBeenCalledTimes(2);
      expect(res.access_token).toEqual(expect.any(String));
      expect(res.refresh_token).toEqual(expect.any(String));
    });

    it('rejects an unknown refresh token with 401', async () => {
      sessions.findOne.mockResolvedValue(null);
      await expect(service.refresh('nope')).rejects.toBeInstanceOf(
        UnauthorizedException,
      );
    });

    it('rejects a revoked refresh token', async () => {
      sessions.findOne.mockResolvedValue({
        userId: 'user-1',
        expiresAt: new Date(Date.now() + 86_400_000),
        revokedAt: new Date(),
      });
      await expect(service.refresh('revoked')).rejects.toBeInstanceOf(
        UnauthorizedException,
      );
    });

    it('rejects an expired refresh token', async () => {
      sessions.findOne.mockResolvedValue({
        userId: 'user-1',
        expiresAt: new Date(Date.now() - 1000),
        revokedAt: null,
      });
      await expect(service.refresh('expired')).rejects.toBeInstanceOf(
        UnauthorizedException,
      );
    });
  });

  describe('logout', () => {
    it('revokes a live session', async () => {
      const session: Partial<AuthSession> = { revokedAt: null };
      sessions.findOne.mockResolvedValue(session);
      await service.logout('token');
      expect(session.revokedAt).toBeInstanceOf(Date);
      expect(sessions.save).toHaveBeenCalledWith(session);
    });

    it('is a no-op (still resolves) for an unknown token', async () => {
      sessions.findOne.mockResolvedValue(null);
      await expect(service.logout('unknown')).resolves.toBeUndefined();
      expect(sessions.save).not.toHaveBeenCalled();
    });
  });

  describe('getMe', () => {
    it('returns free-tier limits with zero usage when no counter row exists', async () => {
      users.findOne.mockResolvedValue(await buildUser());
      usageCounters.findOne.mockResolvedValue(null);

      const res = await service.getMe('user-1');
      expect(res.tier).toBe(Tier.Free);
      expect(res.usage).toEqual({
        replies_used: 0,
        replies_limit: 20,
        screenshots_used: 0,
        screenshots_limit: 5,
      });
    });

    it('reflects an existing counter row', async () => {
      users.findOne.mockResolvedValue(await buildUser());
      usageCounters.findOne.mockResolvedValue({
        repliesUsed: 7,
        screenshotsUsed: 2,
      } as UsageCounter);

      const res = await service.getMe('user-1');
      expect(res.usage.replies_used).toBe(7);
      expect(res.usage.screenshots_used).toBe(2);
    });

    it('reports unlimited (null) limits for a Pro user', async () => {
      users.findOne.mockResolvedValue(await buildUser({ tier: Tier.Pro }));
      usageCounters.findOne.mockResolvedValue(null);

      const res = await service.getMe('user-1');
      expect(res.usage.replies_limit).toBeNull();
      expect(res.usage.screenshots_limit).toBeNull();
    });

    it('rejects when the user no longer exists', async () => {
      users.findOne.mockResolvedValue(null);
      await expect(service.getMe('ghost')).rejects.toBeInstanceOf(
        UnauthorizedException,
      );
    });
  });
});
