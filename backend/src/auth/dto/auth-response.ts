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

/** User-controlled settings (Android Phase 0). */
export interface UserSettingsView {
  /** Store keyboard-captured conversations so the Memory engine learns from them. */
  memory_opt_in: boolean;
  /** Allow stored conversations to be used for model training (docs/model-training.md). */
  training_opt_in: boolean;
}

export function toSettingsView(user: User): UserSettingsView {
  return {
    memory_opt_in: user.memoryOptIn,
    training_opt_in: user.trainingOptIn,
  };
}

/** Response for `GET /me`. */
export interface MeResponse {
  user: PublicUser;
  tier: Tier;
  usage: UsageView;
  settings: UserSettingsView;
}

/**
 * Response for `PATCH /me/settings`. `deleted` is present whenever the
 * request set `memory_opt_in: false` (the keyboard-data purge always runs
 * then; counts may be 0) and absent for `memory_opt_in: true`.
 */
export interface SettingsResponse {
  settings: UserSettingsView;
  deleted?: {
    /** Keyboard-captured conversations erased (messages cascade). */
    conversations: number;
    /** Extracted memories erased because no other source remained. */
    memories: number;
  };
}
