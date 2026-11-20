import { IsBoolean } from 'class-validator';

/**
 * `PATCH /me/settings` body (Android Phase 0). `memory_opt_in` is required
 * (it is the only setting today) and must be a real JSON boolean; strings such
 * as "true" are rejected rather than coerced.
 */
export class UpdateSettingsDto {
  @IsBoolean()
  memory_opt_in!: boolean;
}
