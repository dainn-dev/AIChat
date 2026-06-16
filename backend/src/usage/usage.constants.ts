/**
 * Subscription tier. Owned long-term by the users table (WS-2/WS-3); declared
 * here so the cross-cutting usage module has no hard dependency on that schema
 * yet. Callers pass `user.tier` through to the quota service.
 */
export enum Tier {
  Free = 'free',
  Pro = 'pro',
}

/**
 * A metered action. Each value is the `metric` key persisted on a
 * `usage_counters` row — keep these strings stable, they are part of the data.
 */
export enum UsageMetric {
  Reply = 'reply',
  Screenshot = 'screenshot',
}
