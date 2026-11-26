import { IsBoolean, ValidateIf } from 'class-validator';

/**
 * `PATCH /me/settings` body. Each field is optional but at least one must be
 * sent, and every field sent must be a real JSON boolean (strings such as
 * "true" are rejected rather than coerced):
 *  - `memory_opt_in` (Android Phase 0): store keyboard captures for memory.
 *  - `training_opt_in`: allow stored conversations to be used for model
 *    training (docs/model-training.md).
 */
export class UpdateSettingsDto {
  // Validated when sent, or when it is the only possible field (empty body).
  @ValidateIf(
    (o: UpdateSettingsDto) =>
      o.memory_opt_in !== undefined || o.training_opt_in === undefined,
  )
  @IsBoolean()
  memory_opt_in?: boolean;

  @ValidateIf(
    (o: UpdateSettingsDto) =>
      o.training_opt_in !== undefined || o.memory_opt_in === undefined,
  )
  @IsBoolean()
  training_opt_in?: boolean;
}
