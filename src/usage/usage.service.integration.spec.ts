import { randomUUID } from 'crypto';
import { ConfigService } from '@nestjs/config';
import { DataSource } from 'typeorm';
import { UsageConfig } from '../config/configuration';
import { buildDataSourceOptions } from '../database/data-source-options';
import { QuotaExceededException } from './exceptions/quota-exceeded.exception';
import { Tier, UsageMetric } from './usage.constants';
import { UsageService } from './usage.service';

/**
 * The single highest-value test for WS-6 (DAI-130 AC, DAI-124 §6): under
 * concurrent load the daily counter must NEVER race past the limit.
 *
 * Requires a real PostgreSQL (the guarantee lives in the atomic
 * `INSERT ... ON CONFLICT DO UPDATE ... WHERE count < limit`, not in JS), so it
 * is gated behind USAGE_DB_TEST=1 and skipped in environments without a DB.
 *
 *   USAGE_DB_TEST=1 npm test -- usage.service.integration
 */
const RUN = process.env.USAGE_DB_TEST === '1';
const describeDb = RUN ? describe : describe.skip;

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

describeDb('UsageService (integration, real Postgres)', () => {
  let dataSource: DataSource;
  let service: UsageService;

  beforeAll(async () => {
    dataSource = new DataSource({
      ...buildDataSourceOptions({
        url: process.env.DATABASE_URL,
        host: process.env.DATABASE_HOST ?? 'localhost',
        port: Number(process.env.DATABASE_PORT ?? 5432),
        user: process.env.DATABASE_USER ?? 'aichat',
        password: process.env.DATABASE_PASSWORD ?? 'aichat',
        name: process.env.DATABASE_NAME ?? 'aichat',
        ssl: false,
      }),
    });
    await dataSource.initialize();
    await dataSource.runMigrations();

    const config = {
      getOrThrow: () => USAGE_CONFIG,
    } as unknown as ConfigService;
    service = new UsageService(dataSource, config);
  }, 30_000);

  afterAll(async () => {
    if (dataSource?.isInitialized) await dataSource.destroy();
  });

  const finalCount = async (
    userId: string,
    metric: UsageMetric,
    date: string,
  ): Promise<number> => {
    const rows: Array<{ count: number }> = await dataSource.query(
      `SELECT count FROM usage_counters
        WHERE user_id = $1 AND metric = $2 AND usage_date = $3`,
      [userId, metric, date],
    );
    return rows.length ? Number(rows[0].count) : 0;
  };

  it('never over-counts replies under concurrent load (20 ok, rest rejected)', async () => {
    const userId = randomUUID();
    const now = new Date('2026-06-14T10:00:00Z');
    const ATTEMPTS = 60;

    const results = await Promise.allSettled(
      Array.from({ length: ATTEMPTS }, () =>
        service.reserve(userId, Tier.Free, UsageMetric.Reply, now),
      ),
    );

    const ok = results.filter((r) => r.status === 'fulfilled').length;
    const rejected = results.filter(
      (r) =>
        r.status === 'rejected' && r.reason instanceof QuotaExceededException,
    ).length;

    expect(ok).toBe(20);
    expect(rejected).toBe(ATTEMPTS - 20);
    expect(await finalCount(userId, UsageMetric.Reply, '2026-06-14')).toBe(20);
  });

  it('never over-counts screenshots under concurrent load (5 ok)', async () => {
    const userId = randomUUID();
    const now = new Date('2026-06-14T10:00:00Z');

    const results = await Promise.allSettled(
      Array.from({ length: 25 }, () =>
        service.reserve(userId, Tier.Free, UsageMetric.Screenshot, now),
      ),
    );

    expect(results.filter((r) => r.status === 'fulfilled').length).toBe(5);
    expect(await finalCount(userId, UsageMetric.Screenshot, '2026-06-14')).toBe(
      5,
    );
  });

  it('the 20th reply succeeds and the 21st is rejected (sequential boundary)', async () => {
    const userId = randomUUID();
    const now = new Date('2026-06-14T10:00:00Z');

    for (let i = 0; i < 20; i++) {
      await expect(
        service.reserve(userId, Tier.Free, UsageMetric.Reply, now),
      ).resolves.toBeUndefined();
    }
    await expect(
      service.reserve(userId, Tier.Free, UsageMetric.Reply, now),
    ).rejects.toBeInstanceOf(QuotaExceededException);
  });

  it('resets across the UTC day boundary (AC-Q1)', async () => {
    const userId = randomUUID();
    const day1 = new Date('2026-06-14T10:00:00Z');
    const day2 = new Date('2026-06-15T10:00:00Z');

    for (let i = 0; i < 20; i++) {
      await service.reserve(userId, Tier.Free, UsageMetric.Reply, day1);
    }
    await expect(
      service.reserve(userId, Tier.Free, UsageMetric.Reply, day1),
    ).rejects.toBeInstanceOf(QuotaExceededException);

    // New day = new key = fresh allowance.
    await expect(
      service.reserve(userId, Tier.Free, UsageMetric.Reply, day2),
    ).resolves.toBeUndefined();
    expect(await finalCount(userId, UsageMetric.Reply, '2026-06-15')).toBe(1);
  });

  it('lets Pro exceed Free limits (AC-Q2: unlimited counts)', async () => {
    const userId = randomUUID();
    const now = new Date('2026-06-14T10:00:00Z');

    for (let i = 0; i < 50; i++) {
      await expect(
        service.reserve(userId, Tier.Pro, UsageMetric.Reply, now),
      ).resolves.toBeUndefined();
    }
    expect(await finalCount(userId, UsageMetric.Reply, '2026-06-14')).toBe(50);
  });

  it('refunds a released reservation, freeing the slot again', async () => {
    const userId = randomUUID();
    const now = new Date('2026-06-14T10:00:00Z');

    for (let i = 0; i < 20; i++) {
      await service.reserve(userId, Tier.Free, UsageMetric.Reply, now);
    }
    // At the limit; release one (simulating a failed LLM call) frees a slot.
    await service.release(userId, UsageMetric.Reply, now);
    expect(await finalCount(userId, UsageMetric.Reply, '2026-06-14')).toBe(19);

    await expect(
      service.reserve(userId, Tier.Free, UsageMetric.Reply, now),
    ).resolves.toBeUndefined();
    expect(await finalCount(userId, UsageMetric.Reply, '2026-06-14')).toBe(20);
  });

  it('enforces and accumulates the Pro spend cap', async () => {
    const userId = randomUUID();
    const now = new Date('2026-06-14T10:00:00Z');

    await expect(
      service.assertUnderSpendCap(userId, Tier.Pro, now),
    ).resolves.toBeUndefined();

    await service.recordSpend(userId, 19_999_999, now);
    await expect(
      service.assertUnderSpendCap(userId, Tier.Pro, now),
    ).resolves.toBeUndefined();

    await service.recordSpend(userId, 2, now);
    expect(await service.getSpentMicroUsd(userId, now)).toBe(20_000_001);
    await expect(
      service.assertUnderSpendCap(userId, Tier.Pro, now),
    ).rejects.toBeInstanceOf(Error);
  });
});
