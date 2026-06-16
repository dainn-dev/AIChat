import { ConfigService } from '@nestjs/config';
import { DataSource } from 'typeorm';
import { UsageConfig } from '../config/configuration';
import { QuotaExceededException } from './exceptions/quota-exceeded.exception';
import { SpendCapExceededException } from './exceptions/spend-cap-exceeded.exception';
import { Tier, UsageMetric } from './usage.constants';
import { UsageService, utcDateKey } from './usage.service';

const USAGE_CONFIG: UsageConfig = {
  free: {
    repliesPerDay: 20,
    screenshotsPerDay: 5,
    dailySpendCapMicroUsd: null,
  },
  pro: {
    repliesPerDay: null,
    screenshotsPerDay: null,
    dailySpendCapMicroUsd: 20_000_000,
  },
};

describe('utcDateKey', () => {
  it('formats a date as a UTC YYYY-MM-DD key', () => {
    expect(utcDateKey(new Date('2026-06-14T23:30:00Z'))).toBe('2026-06-14');
    // Just before UTC midnight in a positive offset is still the UTC day.
    expect(utcDateKey(new Date('2026-06-14T23:59:59Z'))).toBe('2026-06-14');
    expect(utcDateKey(new Date('2026-06-15T00:00:01Z'))).toBe('2026-06-15');
  });
});

describe('UsageService', () => {
  let service: UsageService;
  let query: jest.Mock;

  beforeEach(() => {
    query = jest.fn();
    const dataSource = { query } as unknown as DataSource;
    const config = {
      getOrThrow: jest.fn().mockReturnValue(USAGE_CONFIG),
    } as unknown as ConfigService;
    service = new UsageService(dataSource, config);
  });

  describe('reserve', () => {
    const now = new Date('2026-06-14T10:00:00Z');

    it('claims a finite-limit slot with a conditional atomic increment', async () => {
      query.mockResolvedValue([{ count: 1 }]);

      await service.reserve('user-1', Tier.Free, UsageMetric.Reply, now);

      expect(query).toHaveBeenCalledTimes(1);
      const [sql, params] = query.mock.calls[0];
      expect(sql).toContain('ON CONFLICT');
      expect(sql).toContain('usage_counters.count < $4');
      expect(params).toEqual(['user-1', 'reply', '2026-06-14', 20]);
    });

    it('rejects when the conditional update returns no row (limit reached)', async () => {
      query.mockResolvedValue([]);

      await expect(
        service.reserve('user-1', Tier.Free, UsageMetric.Reply, now),
      ).rejects.toBeInstanceOf(QuotaExceededException);
    });

    it('does not apply a guard for an unlimited (Pro) tier', async () => {
      query.mockResolvedValue([{ count: 999 }]);

      await service.reserve('user-1', Tier.Pro, UsageMetric.Reply, now);

      const [sql, params] = query.mock.calls[0];
      expect(sql).not.toContain('count <');
      expect(params).toEqual(['user-1', 'reply', '2026-06-14']);
    });

    it('rejects a non-positive limit without touching the database', async () => {
      const config = {
        getOrThrow: jest.fn().mockReturnValue({
          ...USAGE_CONFIG,
          free: { ...USAGE_CONFIG.free, repliesPerDay: 0 },
        }),
      } as unknown as ConfigService;
      const disabled = new UsageService(
        { query } as unknown as DataSource,
        config,
      );

      await expect(
        disabled.reserve('user-1', Tier.Free, UsageMetric.Reply, now),
      ).rejects.toBeInstanceOf(QuotaExceededException);
      expect(query).not.toHaveBeenCalled();
    });
  });

  describe('release', () => {
    const now = new Date('2026-06-14T10:00:00Z');

    it('decrements the counter, flooring at zero', async () => {
      query.mockResolvedValue([]);

      await service.release('user-1', UsageMetric.Screenshot, now);

      const [sql, params] = query.mock.calls[0];
      expect(sql).toContain('GREATEST(count - 1, 0)');
      expect(params).toEqual(['user-1', 'screenshot', '2026-06-14']);
    });

    it('swallows database errors so it never masks the original failure', async () => {
      query.mockRejectedValue(new Error('db down'));

      await expect(
        service.release('user-1', UsageMetric.Reply, now),
      ).resolves.toBeUndefined();
    });
  });

  describe('runWithQuota', () => {
    const now = new Date('2026-06-14T10:00:00Z');

    it('reserves, runs the work, and keeps the reservation on success', async () => {
      query.mockResolvedValue([{ count: 1 }]);
      const work = jest.fn().mockResolvedValue('reply!');

      const result = await service.runWithQuota(
        'user-1',
        Tier.Free,
        UsageMetric.Reply,
        work,
        now,
      );

      expect(result).toBe('reply!');
      expect(work).toHaveBeenCalledTimes(1);
      // Only the reserve query — no release.
      expect(query).toHaveBeenCalledTimes(1);
    });

    it('releases the reservation and rethrows when the work fails', async () => {
      query.mockResolvedValue([{ count: 1 }]);
      const boom = new Error('LLM upstream 502');
      const work = jest.fn().mockRejectedValue(boom);

      await expect(
        service.runWithQuota('user-1', Tier.Free, UsageMetric.Reply, work, now),
      ).rejects.toBe(boom);

      // reserve + release.
      expect(query).toHaveBeenCalledTimes(2);
      expect(query.mock.calls[1][0]).toContain('GREATEST(count - 1, 0)');
    });

    it('does not run the work when the reservation is rejected', async () => {
      query.mockResolvedValue([]);
      const work = jest.fn();

      await expect(
        service.runWithQuota('user-1', Tier.Free, UsageMetric.Reply, work, now),
      ).rejects.toBeInstanceOf(QuotaExceededException);
      expect(work).not.toHaveBeenCalled();
    });
  });

  describe('spend cap', () => {
    const now = new Date('2026-06-14T10:00:00Z');

    it('is a no-op for tiers with no cap configured (Free)', async () => {
      await service.assertUnderSpendCap('user-1', Tier.Free, now);
      expect(query).not.toHaveBeenCalled();
    });

    it('passes when spend is below the Pro cap', async () => {
      query.mockResolvedValue([{ spent_micro_usd: '5000000' }]);
      await expect(
        service.assertUnderSpendCap('user-1', Tier.Pro, now),
      ).resolves.toBeUndefined();
    });

    it('throws once spend reaches the Pro cap', async () => {
      query.mockResolvedValue([{ spent_micro_usd: '20000000' }]);
      await expect(
        service.assertUnderSpendCap('user-1', Tier.Pro, now),
      ).rejects.toBeInstanceOf(SpendCapExceededException);
    });

    it('records spend with an atomic upsert, rounding to whole micro-USD', async () => {
      query.mockResolvedValue([]);
      await service.recordSpend('user-1', 1234.6, now);

      const [sql, params] = query.mock.calls[0];
      expect(sql).toContain('INSERT INTO spend_counters');
      expect(sql).toContain('ON CONFLICT');
      expect(params).toEqual(['user-1', '2026-06-14', 1235]);
    });

    it('ignores non-positive spend amounts', async () => {
      await service.recordSpend('user-1', 0, now);
      await service.recordSpend('user-1', -10, now);
      expect(query).not.toHaveBeenCalled();
    });
  });

  describe('getUsageSummary', () => {
    const now = new Date('2026-06-14T10:00:00Z');

    it('maps today counts against Free limits, defaulting missing metrics to 0', async () => {
      query.mockResolvedValue([{ metric: 'reply', count: 7 }]);

      const summary = await service.getUsageSummary('user-1', Tier.Free, now);

      expect(summary).toEqual({
        replies_used: 7,
        replies_limit: 20,
        screenshots_used: 0,
        screenshots_limit: 5,
      });
    });

    it('reports null limits for the unlimited Pro tier', async () => {
      query.mockResolvedValue([
        { metric: 'reply', count: 50 },
        { metric: 'screenshot', count: 12 },
      ]);

      const summary = await service.getUsageSummary('user-1', Tier.Pro, now);

      expect(summary).toEqual({
        replies_used: 50,
        replies_limit: null,
        screenshots_used: 12,
        screenshots_limit: null,
      });
    });
  });
});
