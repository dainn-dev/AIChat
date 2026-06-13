/**
 * Monetization tiers and their daily usage limits (DAI-124 §1, epic DAI-119).
 *
 *   Free → 20 replies/day, 5 screenshots/day
 *   Pro  → unlimited (represented as `null`)
 *
 * Limits are surfaced by `GET /me`; the actual per-request enforcement and
 * counter increments live in the AI pipeline / screenshot workstreams
 * (WS-4 / WS-5). A `null` limit means "no cap".
 */
export enum Tier {
  Free = 'free',
  Pro = 'pro',
}

export interface TierLimits {
  /** Daily reply quota, or `null` for unlimited. */
  repliesPerDay: number | null;
  /** Daily screenshot/analysis quota, or `null` for unlimited. */
  screenshotsPerDay: number | null;
}

export const TIER_LIMITS: Record<Tier, TierLimits> = {
  [Tier.Free]: { repliesPerDay: 20, screenshotsPerDay: 5 },
  [Tier.Pro]: { repliesPerDay: null, screenshotsPerDay: null },
};
