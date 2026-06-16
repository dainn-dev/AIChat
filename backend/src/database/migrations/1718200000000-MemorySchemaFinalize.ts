import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * MS-2 — Memory schema finalize + pgvector ANN index (DAI-147, Phase 2 / DAI-121).
 *
 * Stacks on top of the now-healthy migration chain (CoreSchema owns the base
 * `memories` table; DAI-141/DAI-145 reconciled the collisions). This migration
 * finalizes `memories` for the Phase-2 Memory Engine and is the only place the
 * embedding dimension and the ANN index are pinned.
 *
 * Embedding dimension N is fixed at **1536** (cosine), the working decision
 * carried from MS-1. If the owner overrides N pre-merge, change the single
 * `1536` literal in the `ALTER COLUMN ... TYPE vector(N)` op below — the HNSW
 * index is dimension-agnostic and needs no edit.
 *
 * Deliberate choices:
 * - `embedding` is widened from the dimensionless `vector` CoreSchema declared
 *   to `vector(1536)`. HNSW requires a fixed dimension, and the table is empty
 *   in Phase 1, so the in-place type change is safe. It stays NULLABLE: FR-E4
 *   inserts the row first and backfills the embedding on a later retry.
 * - The dedupe key for idempotent extraction (FR-M5) is a PARTIAL unique index
 *   on `(user_id, contact_label, kind, content_hash)`. It is partial
 *   (`WHERE content_hash IS NOT NULL`) so manually-authored memories that carry
 *   no hash are never constrained, and `NULLS NOT DISTINCT` so a NULL
 *   `contact_label` is treated as one scope rather than as infinitely-distinct
 *   rows (PG15+; CI runs pg16).
 * - CoreSchema's single-column `idx_memories_user` is dropped: the new
 *   `(user_id, contact_label)` scope index (FR-RT2) has `user_id` as its
 *   leftmost column and fully covers user-only lookups, so keeping both would
 *   be a redundant write-amplifying index. `down` restores the original.
 */
export class MemorySchemaFinalize1718200000000 implements MigrationInterface {
  name = 'MemorySchemaFinalize1718200000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    // ── Enum types (mirror CoreSchema's native-enum convention) ────────
    await queryRunner.query(
      `CREATE TYPE "memory_source" AS ENUM ('extracted', 'manual');`,
    );
    await queryRunner.query(
      `CREATE TYPE "memory_status" AS ENUM ('active', 'pending_review', 'dismissed');`,
    );

    // ── New columns on memories (FR-E2, FR-M5) ─────────────────────────
    await queryRunner.query(`
      ALTER TABLE "memories"
        ADD COLUMN "source" "memory_source" NOT NULL DEFAULT 'extracted',
        ADD COLUMN "confidence" real,
        ADD COLUMN "status" "memory_status" NOT NULL DEFAULT 'active',
        ADD COLUMN "embedding_model" text,
        ADD COLUMN "updated_at" timestamptz NOT NULL DEFAULT now(),
        ADD COLUMN "content_hash" text,
        ADD CONSTRAINT "memories_confidence_range"
          CHECK ("confidence" IS NULL OR ("confidence" >= 0 AND "confidence" <= 1));
    `);

    // ── Pin the embedding dimension so HNSW can index it (FR-E3) ───────
    // N = 1536 (cosine). Empty table in Phase 1 → in-place type change is safe.
    await queryRunner.query(`
      ALTER TABLE "memories"
        ALTER COLUMN "embedding" TYPE vector(1536) USING "embedding"::vector(1536);
    `);

    // ── Idempotent-extraction dedupe key (FR-M5) ──────────────────────
    await queryRunner.query(`
      CREATE UNIQUE INDEX "uq_memories_content_hash"
        ON "memories" ("user_id", "contact_label", "kind", "content_hash")
        NULLS NOT DISTINCT
        WHERE "content_hash" IS NOT NULL;
    `);

    // ── Scope filter for retrieval (FR-RT2) ───────────────────────────
    await queryRunner.query(`
      CREATE INDEX "idx_memories_user_contact"
        ON "memories" ("user_id", "contact_label");
    `);
    // Now redundant: (user_id, contact_label) covers user-only lookups.
    await queryRunner.query(`DROP INDEX "idx_memories_user";`);

    // ── HNSW ANN index for Top-K cosine retrieval (FR-E3) ─────────────
    await queryRunner.query(`
      CREATE INDEX "idx_memories_embedding_hnsw"
        ON "memories" USING hnsw ("embedding" vector_cosine_ops);
    `);

    // ── memory_extractions audit table (recommended in DAI-121 §2) ────
    await queryRunner.query(`
      CREATE TABLE "memory_extractions" (
        "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        "user_id" uuid NOT NULL,
        "conversation_id" uuid,
        "model" text,
        "tokens" integer,
        "n_memories" integer NOT NULL DEFAULT 0,
        "created_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "fk_memory_extractions_user" FOREIGN KEY ("user_id")
          REFERENCES "users" ("id") ON DELETE CASCADE,
        CONSTRAINT "fk_memory_extractions_conversation" FOREIGN KEY ("conversation_id")
          REFERENCES "conversations" ("id") ON DELETE SET NULL
      );
    `);
    await queryRunner.query(
      `CREATE INDEX "idx_memory_extractions_user" ON "memory_extractions" ("user_id");`,
    );
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    // Reverse order: audit table, indexes, embedding type, columns, enums.
    await queryRunner.query(`DROP TABLE IF EXISTS "memory_extractions";`);

    await queryRunner.query(
      `DROP INDEX IF EXISTS "idx_memories_embedding_hnsw";`,
    );
    await queryRunner.query(
      `DROP INDEX IF EXISTS "idx_memories_user_contact";`,
    );
    // Restore CoreSchema's original single-column index.
    await queryRunner.query(
      `CREATE INDEX "idx_memories_user" ON "memories" ("user_id");`,
    );
    await queryRunner.query(`DROP INDEX IF EXISTS "uq_memories_content_hash";`);

    // Widen the embedding back to a dimensionless vector.
    await queryRunner.query(`
      ALTER TABLE "memories"
        ALTER COLUMN "embedding" TYPE vector USING "embedding"::vector;
    `);

    await queryRunner.query(`
      ALTER TABLE "memories"
        DROP CONSTRAINT IF EXISTS "memories_confidence_range",
        DROP COLUMN "content_hash",
        DROP COLUMN "updated_at",
        DROP COLUMN "embedding_model",
        DROP COLUMN "status",
        DROP COLUMN "confidence",
        DROP COLUMN "source";
    `);

    await queryRunner.query(`DROP TYPE IF EXISTS "memory_status";`);
    await queryRunner.query(`DROP TYPE IF EXISTS "memory_source";`);
  }
}
