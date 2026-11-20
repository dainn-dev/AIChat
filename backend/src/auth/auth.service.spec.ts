import {
  ConflictException,
  ServiceUnavailableException,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import * as bcrypt from 'bcryptjs';
import { AuthService } from './auth.service';
import { AuthConfig } from '../config/configuration';
import { Tier } from '../common/tiers';
import { UsageService } from '../usage/usage.service';
import { UsageSummary } from '../usage/dto/usage-summary';
import { AuthSession } from './entities/auth-session.entity';
import { User } from './entities/user.entity';
import { ConversationErasureService } from '../conversations/conversation-erasure.service';

/** Free-tier usage block with zero consumption, the common test default. */
const ZERO_FREE_USAGE: UsageSummary = {
  replies_used: 0,
  replies_limit: 20,
  screenshots_used: 0,
  screenshots_limit: 5,
};

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
    update: jest.fn(),
    manager: { transaction: jest.fn() },
  };
}

describe('AuthService', () => {
  let service: AuthService;
  let users: ReturnType<typeof makeRepo>;
  let sessions: ReturnType<typeof makeRepo>;
  let usage: { getUsageSummary: jest.Mock };
  let jwt: JwtService;
  let erasure: {
    purgeKeyboardConversations: jest.Mock;
    applyPurgeTimeouts: jest.Mock;
  };

  const buildUser = async (over: Partial<User> = {}): Promise<User> =>
    ({
      id: 'user-1',
      email: 'a@b.com',
      passwordHash: await bcrypt.hash('password123', 4),
      displayName: null,
      tier: Tier.Free,
      memoryOptIn: false,
      createdAt: new Date('2026-01-01T00:00:00Z'),
      updatedAt: new Date('2026-01-01T00:00:00Z'),
      ...over,
    }) as User;

  beforeEach(() => {
    users = makeRepo();
    sessions = makeRepo();
    usage = { getUsageSummary: jest.fn().mockResolvedValue(ZERO_FREE_USAGE) };
    jwt = new JwtService({});
    erasure = {
      purgeKeyboardConversations: jest
        .fn()
        .mockResolvedValue({ conversations: 2, memories: 1 }),
      applyPurgeTimeouts: jest.fn().mockResolvedValue(undefined),
    };

    const config = {
      getOrThrow: jest.fn().mockReturnValue(AUTH_CONFIG),
    } as unknown as ConfigService;

    service = new AuthService(
      users as never,
      sessions as never,
      usage as unknown as UsageService,
      jwt,
      config,
      erasure as unknown as ConversationErasureService,
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
    it('returns the usage block UsageService reports for the user and tier', async () => {
      users.findOne.mockResolvedValue(await buildUser());
      usage.getUsageSummary.mockResolvedValue(ZERO_FREE_USAGE);

      const res = await service.getMe('user-1');
      expect(res.tier).toBe(Tier.Free);
      expect(res.usage).toEqual(ZERO_FREE_USAGE);
      // Reads the canonical metric-based counters via UsageService, passing the
      // user's id and tier through.
      expect(usage.getUsageSummary).toHaveBeenCalledWith('user-1', Tier.Free);
    });

    it('reflects the counts UsageService returns', async () => {
      users.findOne.mockResolvedValue(await buildUser());
      usage.getUsageSummary.mockResolvedValue({
        replies_used: 7,
        replies_limit: 20,
        screenshots_used: 2,
        screenshots_limit: 5,
      } as UsageSummary);

      const res = await service.getMe('user-1');
      expect(res.usage.replies_used).toBe(7);
      expect(res.usage.screenshots_used).toBe(2);
    });

    it('reports unlimited (null) limits for a Pro user', async () => {
      users.findOne.mockResolvedValue(await buildUser({ tier: Tier.Pro }));
      usage.getUsageSummary.mockResolvedValue({
        replies_used: 0,
        replies_limit: null,
        screenshots_used: 0,
        screenshots_limit: null,
      } as UsageSummary);

      const res = await service.getMe('user-1');
      expect(usage.getUsageSummary).toHaveBeenCalledWith('user-1', Tier.Pro);
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

  describe('settings (Android Phase 0)', () => {
    it('GET /me exposes memory_opt_in (defaults to false)', async () => {
      users.findOne.mockResolvedValue(await buildUser());
      const res = await service.getMe('user-1');
      expect(res.settings).toEqual({ memory_opt_in: false });
    });

    it('GET /me reflects an opted-in user', async () => {
      users.findOne.mockResolvedValue(await buildUser({ memoryOptIn: true }));
      const res = await service.getMe('user-1');
      expect(res.settings.memory_opt_in).toBe(true);
    });

    /**
     * Wires `users.manager.transaction` to a fake manager whose user-row
     * `SELECT ... FOR NO KEY UPDATE` finds the user (or not).
     */
    const withUser = (exists = true) => {
      const manager = {
        query: jest.fn(async (sql: string) => {
          if (/FROM users WHERE id = \$1 FOR NO KEY UPDATE/.test(sql)) {
            return exists ? [{ id: 'user-1' }] : [];
          }
          return [];
        }),
      };
      users.manager.transaction.mockImplementation(async (work) =>
        work(manager),
      );
      return manager;
    };

    it('opting in locks the caller row, writes the flag and purges nothing', async () => {
      const manager = withUser();
      const res = await service.updateSettings('user-1', {
        memory_opt_in: true,
      });
      expect(manager.query).toHaveBeenNthCalledWith(
        1,
        expect.stringContaining('FOR NO KEY UPDATE'),
        ['user-1'],
      );
      expect(manager.query).toHaveBeenNthCalledWith(
        2,
        expect.stringContaining('UPDATE users SET memory_opt_in = $2'),
        ['user-1', true],
      );
      expect(res).toEqual({ settings: { memory_opt_in: true } });
      expect(res).not.toHaveProperty('deleted');
      expect(erasure.purgeKeyboardConversations).not.toHaveBeenCalled();
      expect(erasure.applyPurgeTimeouts).not.toHaveBeenCalled();
    });

    it('every memory_opt_in:false purges in the same transaction (also legacy false -> false)', async () => {
      const manager = withUser();
      const res = await service.updateSettings('user-1', {
        memory_opt_in: false,
      });
      expect(erasure.purgeKeyboardConversations).toHaveBeenCalledWith(
        manager,
        'user-1',
      );
      expect(res).toEqual({
        settings: { memory_opt_in: false },
        deleted: { conversations: 2, memories: 1 },
      });
    });

    it('bounds the purge transaction: timeouts are set before the row lock', async () => {
      const manager = withUser();
      const order: string[] = [];
      erasure.applyPurgeTimeouts.mockImplementation(async () => {
        order.push('timeouts');
      });
      manager.query.mockImplementation(async (sql: string) => {
        order.push(/FOR NO KEY UPDATE/.test(sql) ? 'lock' : 'other');
        return /FOR NO KEY UPDATE/.test(sql) ? [{ id: 'user-1' }] : [];
      });
      await service.updateSettings('user-1', { memory_opt_in: false });
      expect(erasure.applyPurgeTimeouts).toHaveBeenCalledWith(manager);
      expect(order.slice(0, 2)).toEqual(['timeouts', 'lock']);
    });

    it('retries the opt-out transaction once on a deadlock (40P01)', async () => {
      const manager = withUser();
      users.manager.transaction
        .mockRejectedValueOnce(
          Object.assign(new Error('deadlock'), { code: '40P01' }),
        )
        .mockImplementation(async (work) => work(manager));
      const res = await service.updateSettings('user-1', {
        memory_opt_in: false,
      });
      expect(users.manager.transaction).toHaveBeenCalledTimes(2);
      expect(res.deleted).toEqual({ conversations: 2, memories: 1 });
    });

    it('gives up after a second deadlock', async () => {
      const deadlock = Object.assign(new Error('deadlock'), { code: '40P01' });
      users.manager.transaction.mockRejectedValue(deadlock);
      await expect(
        service.updateSettings('user-1', { memory_opt_in: false }),
      ).rejects.toBe(deadlock);
      expect(users.manager.transaction).toHaveBeenCalledTimes(2);
    });

    it.each(['55P03', '57014'])(
      'maps a purge timeout (%s) to 503 ERASURE_TIMEOUT without retrying',
      async (code) => {
        users.manager.transaction.mockRejectedValue(
          Object.assign(new Error('timeout'), { code }),
        );
        const err = await service
          .updateSettings('user-1', { memory_opt_in: false })
          .catch((e: unknown) => e);
        expect(err).toBeInstanceOf(ServiceUnavailableException);
        expect((err as ServiceUnavailableException).getResponse()).toEqual(
          expect.objectContaining({ code: 'ERASURE_TIMEOUT' }),
        );
        expect(users.manager.transaction).toHaveBeenCalledTimes(1);
      },
    );

    it('updateSettings rejects when the user no longer exists', async () => {
      withUser(false);
      await expect(
        service.updateSettings('ghost', { memory_opt_in: false }),
      ).rejects.toBeInstanceOf(UnauthorizedException);
      expect(erasure.purgeKeyboardConversations).not.toHaveBeenCalled();
    });

    it('propagates a purge failure (transaction rolls back the flag)', async () => {
      withUser();
      erasure.purgeKeyboardConversations.mockRejectedValue(new Error('boom'));
      await expect(
        service.updateSettings('user-1', { memory_opt_in: false }),
      ).rejects.toThrow('boom');
    });
  });
});
