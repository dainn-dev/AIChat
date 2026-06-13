import { User } from '../entities/user.entity';
import { Tier } from '../../common/tiers';

/**
 * Client-safe projection of a {@link User}. Deliberately omits `passwordHash`
 * and any other sensitive column.
 */
export interface PublicUser {
  id: string;
  email: string;
  display_name: string | null;
  tier: Tier;
  created_at: string;
}

export function toPublicUser(user: User): PublicUser {
  return {
    id: user.id,
    email: user.email,
    display_name: user.displayName,
    tier: user.tier,
    created_at: user.createdAt.toISOString(),
  };
}

/** Response for `/auth/signup` and `/auth/login`. */
export interface AuthTokensResponse {
  user: PublicUser;
  access_token: string;
  refresh_token: string;
}

/** Response for `/auth/refresh`. */
export interface RefreshResponse {
  access_token: string;
  refresh_token: string;
}

/** Usage block surfaced by `GET /me`. `*_limit` is `null` for unlimited (Pro). */
export interface UsageView {
  replies_used: number;
  replies_limit: number | null;
  screenshots_used: number;
  screenshots_limit: number | null;
}

/** Response for `GET /me`. */
export interface MeResponse {
  user: PublicUser;
  tier: Tier;
  usage: UsageView;
}
