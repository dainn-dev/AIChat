import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Android Phase 0 — remember WHICH memory superseded which.
 *
 * MS-4's latest-wins rule marks an older singular fact (job/birthday)
 * `superseded` when a newer one arrives. When that newer memory is later
 * erased (conversation erasure or `DELETE /memories/:id`), the older fact
 * should become `active` again instead of staying hidden forever.
 * `superseded_by` stores the id of the superseding memory.
 *
 * Deliberately NO foreign key: erasure reads `superseded_by` to reactivate
 * rows right before deleting the superseding memory, and a dangling id (e.g.
 * if a superseding row disappears some other way) is harmless. Not
 * backfilled: rows superseded before this migration have no recorded link and
 * stay `superseded` (documented gap).
 *
 * The partial index is created non-concurrently inside the migration
 * transaction (TypeORM default). Every row has superseded_by NULL at creation,
 * so the partial index is empty and the brief lock is negligible.
 */
export class MemorySupersededBy1719000000000 implements MigrationInterface {
  name = 'MemorySupersededBy1719000000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "memories" ADD COLUMN "superseded_by" uuid;`,
    );
    await queryRunner.query(`
      CREATE INDEX "idx_memories_superseded_by"
        ON "memories" ("superseded_by")
        WHERE "superseded_by" IS NOT NULL;
    `);
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `DROP INDEX IF EXISTS "idx_memories_superseded_by";`,
    );
    await queryRunner.query(
      `ALTER TABLE "memories" DROP COLUMN IF EXISTS "superseded_by";`,
    );
  }
}
