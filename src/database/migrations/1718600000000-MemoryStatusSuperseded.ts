import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * MS-4 (DAI-149) — add a `superseded` value to the `memory_status` enum.
 *
 * MS-2 created `memory_status` as ('active','pending_review','dismissed'). The
 * extraction worker's latest-wins rule (§5.3) retires an older contradictory
 * singular fact (job/birthday) without deleting it — it is marked `superseded`
 * so it is no longer retrievable (MS-3 reads only `active`) while provenance is
 * kept. `ADD VALUE IF NOT EXISTS` is idempotent and not used in this migration,
 * so it is transaction-safe on PG 12+.
 */
export class MemoryStatusSuperseded1718600000000 implements MigrationInterface {
  name = 'MemoryStatusSuperseded1718600000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TYPE "memory_status" ADD VALUE IF NOT EXISTS 'superseded';`,
    );
  }

  public async down(): Promise<void> {
    // Postgres does not support removing enum values; intentionally a no-op.
  }
}
