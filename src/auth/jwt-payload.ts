import { Tier } from '../common/tiers';

/**
 * Claims carried by the short-lived access JWT. `sub` is the user id.
 * `iat`/`exp` are added by `jsonwebtoken`.
 */
export interface AccessTokenPayload {
  sub: string;
  email: string;
  tier: Tier;
}

/** The request user attached by {@link JwtAuthGuard}. */
export type AuthenticatedUser = AccessTokenPayload;
