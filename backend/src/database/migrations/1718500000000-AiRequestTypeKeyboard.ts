import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * P4-1 (DAI-136) — align the `ai_request_type` enum with the pipeline's request
 * kinds so audit rows can persist once WS-2 wires `AiRequestLogger.persist`.
 *
 * The enum was created as ('reply','analysis','coach'), but the pipeline already
 * emits 'rewrite' and now 'translate' (keyboard surface). We add the missing
 * values; 'coach' is left in place (reserved). `ADD VALUE IF NOT EXISTS` is
 * idempotent and is not used within this migration, so it is transaction-safe on
 * PG 12+.
 */
export class AiRequestTypeKeyboard1718500000000 implements MigrationInterface {
  name = 'AiRequestTypeKeyboard1718500000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TYPE "ai_request_type" ADD VALUE IF NOT EXISTS 'rewrite';`,
    );
    await queryRunner.query(
      `ALTER TYPE "ai_request_type" ADD VALUE IF NOT EXISTS 'translate';`,
    );
  }

  public async down(): Promise<void> {
    // Postgres does not support removing enum values; intentionally a no-op.
    // The added values are harmless if unused.
  }
}
