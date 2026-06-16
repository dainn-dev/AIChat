import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { InjectDataSource } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { TierLimits, UsageConfig } from '../config/configuration';
import { UsageSummary } from './dto/usage-summary';
import { QuotaExceededException } from './exceptions/quota-exceeded.exception';
import { SpendCapExceededException } from './exceptions/spend-cap-exceeded.exception';
import { Tier, UsageMetric } from './usage.constants';

/** Returns today's date as a UTC `YYYY-MM-DD` string used to key counters. */
export function utcDateKey(now: Date = new Date()): string {
  return now.toISOString().slice(0, 10);
}

/**
 * Enforces per-tier daily quotas and the per-user spend cap.
 *
 * ## The concurrency contract (DAI-130 AC, DAI-124 §6)
 *
 * Two requirements pull against each other: limits must be enforced *before*
 * the LLM/OCR call, yet a counter must only advance on *success*. A naive
 * "check, then call, then increment" lets N concurrent requests all pass the
 * check and overshoot the limit. We resolve this with reserve → release:
 *
 *  1. `reserve()` atomically claims a slot via a single
 *     `INSERT ... ON CONFLICT DO UPDATE ... WHERE count < limit`. The row lock
 *     taken by `ON CONFLICT` serializes concurrent claims, so the limit can
 *     never be exceeded — the (limit+1)-th claim updates zero rows and is
 *     rejected. Enforcement happens before the work runs.
 *  2. On success the reservation simply stands (net: incremented on success).
 *  3. On failure `release()` atomically decrements, refunding the slot.
 *
 * `runWithQuota()` wraps that lifecycle so callers (WS-4 reply, WS-5 OCR) get
 * the guarantee for free.
 */
@Injectable()
export class UsageService {
  private readonly logger = new Logger(UsageService.name);

  constructor(
    @InjectDataSource() private readonly dataSource: DataSource,
    private readonly config: ConfigService,
  ) {}

  private limitsFor(tier: Tier): TierLimits {
    const usage = this.config.getOrThrow<UsageConfig>('usage');
    return tier === Tier.Pro ? usage.pro : usage.free;
  }

  private limitForMetric(tier: Tier, metric: UsageMetric): number | null {
    const limits = this.limitsFor(tier);
    return metric === UsageMetric.Reply
      ? limits.repliesPerDay
      : limits.screenshotsPerDay;
  }

  /**
   * Atomically claim one unit of `metric` for today, enforcing the tier limit.
   * Throws {@link QuotaExceededException} if the limit is already reached.
   * No-op for unlimited tiers (count is still advanced for reporting).
   */
  async reserve(
    userId: string,
    tier: Tier,
    metric: UsageMetric,
    now: Date = new Date(),
  ): Promise<void> {
    const limit = this.limitForMetric(tier, metric);
    const date = utcDateKey(now);

    // A non-positive limit means the action is disabled entirely.
    if (limit !== null && limit <= 0) {
      throw new QuotaExceededException(metric, limit);
    }

    // Conditional atomic increment. On a fresh day the INSERT seeds count=1;
    // otherwise DO UPDATE bumps the existing row, but only while it is still
    // under the limit. A finite limit that is already reached updates nothing
    // and RETURNING yields no row.
    const guard = limit === null ? '' : 'WHERE usage_counters.count < $4';
    const params: unknown[] = [userId, metric, date];
    if (limit !== null) params.push(limit);

    const rows: Array<{ count: number }> = await this.dataSource.query(
      `INSERT INTO usage_counters (user_id, metric, usage_date, count)
       VALUES ($1, $2, $3, 1)
       ON CONFLICT (user_id, metric, usage_date)
       DO UPDATE SET count = usage_counters.count + 1, updated_at = now()
       ${guard}
       RETURNING count`,
      params,
    );

    if (rows.length === 0) {
      // limit is finite here (unlimited path always returns a row).
      throw new QuotaExceededException(metric, limit as number);
    }
  }

  /**
   * Refund a previously reserved unit (call when the downstream LLM/OCR work
   * failed). Floors at zero and never throws on its own — a failed release must
   * not mask the original error.
   */
  async release(
    userId: string,
    metric: UsageMetric,
    now: Date = new Date(),
  ): Promise<void> {
    const date = utcDateKey(now);
    try {
      await this.dataSource.query(
        `UPDATE usage_counters
            SET count = GREATEST(count - 1, 0), updated_at = now()
          WHERE user_id = $1 AND metric = $2 AND usage_date = $3`,
        [userId, metric, date],
      );
    } catch (err) {
      this.logger.error(
        `Failed to release ${metric} reservation for user ${userId}`,
        err instanceof Error ? err.stack : String(err),
      );
    }
  }

  /**
   * Reserve a slot, run `work`, and keep the reservation only if it succeeds.
   * This is the integration point for the AI pipeline: limits are enforced
   * before `work` runs and the counter nets to "incremented on success".
   */
  async runWithQuota<T>(
    userId: string,
    tier: Tier,
    metric: UsageMetric,
    work: () => Promise<T>,
    now: Date = new Date(),
  ): Promise<T> {
    await this.reserve(userId, tier, metric, now);
    try {
      return await work();
    } catch (err) {
      await this.release(userId, metric, now);
      throw err;
    }
  }

  /**
   * Reject before doing paid work if the user has already reached their daily
   * spend cap. Best-effort pre-check (exact cost is unknown until the call
   * returns); the hard accounting happens in {@link recordSpend}. No-op when
   * the tier has no cap configured.
   */
  async assertUnderSpendCap(
    userId: string,
    tier: Tier,
    now: Date = new Date(),
  ): Promise<void> {
    const cap = this.limitsFor(tier).dailySpendCapMicroUsd;
    if (cap === null) return;

    const spent = await this.getSpentMicroUsd(userId, now);
    if (spent >= cap) {
      throw new SpendCapExceededException(cap);
    }
  }

  /** Atomically add actual spend (micro-USD) for today. Call on success. */
  async recordSpend(
    userId: string,
    microUsd: number,
    now: Date = new Date(),
  ): Promise<void> {
    if (!Number.isFinite(microUsd) || microUsd <= 0) return;
    const amount = Math.round(microUsd);
    const date = utcDateKey(now);
    await this.dataSource.query(
      `INSERT INTO spend_counters (user_id, usage_date, spent_micro_usd)
       VALUES ($1, $2, $3)
       ON CONFLICT (user_id, usage_date)
       DO UPDATE SET spent_micro_usd = spend_counters.spent_micro_usd + $3,
                     updated_at = now()`,
      [userId, date, amount],
    );
  }

  /** Current accumulated spend (micro-USD) for the user today. */
  async getSpentMicroUsd(
    userId: string,
    now: Date = new Date(),
  ): Promise<number> {
    const date = utcDateKey(now);
    const rows: Array<{ spent_micro_usd: string }> =
      await this.dataSource.query(
        `SELECT spent_micro_usd FROM spend_counters
          WHERE user_id = $1 AND usage_date = $2`,
        [userId, date],
      );
    return rows.length ? Number(rows[0].spent_micro_usd) : 0;
  }

  /**
   * Build the usage block for `GET /me` / AI responses: today's used counts
   * against each tier limit (`null` limit = unlimited).
   */
  async getUsageSummary(
    userId: string,
    tier: Tier,
    now: Date = new Date(),
  ): Promise<UsageSummary> {
    const date = utcDateKey(now);
    const rows: Array<{ metric: string; count: number }> =
      await this.dataSource.query(
        `SELECT metric, count FROM usage_counters
          WHERE user_id = $1 AND usage_date = $2`,
        [userId, date],
      );

    const used = (metric: UsageMetric): number =>
      rows.find((r) => r.metric === metric)?.count ?? 0;

    const limits = this.limitsFor(tier);
    return {
      replies_used: used(UsageMetric.Reply),
      replies_limit: limits.repliesPerDay,
      screenshots_used: used(UsageMetric.Screenshot),
      screenshots_limit: limits.screenshotsPerDay,
    };
  }
}
