/**
 * Monetization tiers (DAI-124 §1, epic DAI-119).
 *
 *   Free → metered daily quotas (replies / screenshots)
 *   Pro  → unlimited
 *
 * The concrete per-tier limits live in config (`usage.free` / `usage.pro`) and
 * are owned by `UsageService` — the single source of truth that both enforces
 * quotas and reports them via `GET /me`. This module only defines the tier
 * enum itself, which is shared across auth, usage and the AI pipeline.
 */
export enum Tier {
  Free = 'free',
  Pro = 'pro',
}
