import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Android Phase 0 — exact memory provenance for conversation erasure.
 *
 * `memories.source_ref` holds a single `conversation:<id>`, but MS-4 dedupes
 * facts (content_hash), so one memory can be derived from several
 * conversations. `memory_sources` records every (memory, conversation) pair so
 * `DELETE /conversations/:id` can remove exactly the memories left with NO
 * remaining source. Both FKs cascade, and the primary key makes each pair
 * unique (idempotent re-extraction).
 *
 * Backfill: one link per existing extracted memory whose `source_ref` points
 * at a conversation that still exists and belongs to the same user. Memories
 * whose source conversation is already gone, and manual memories, get none.
 */
export class MemorySources1718900000000 implements MigrationInterface {
  name = 'MemorySources1718900000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`
      CREATE TABLE "memory_sources" (
        "memory_id" uuid NOT NULL,
        "conversation_id" uuid NOT NULL,
        "created_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "pk_memory_sources" PRIMARY KEY ("memory_id", "conversation_id"),
        CONSTRAINT "fk_memory_sources_memory" FOREIGN KEY ("memory_id")
          REFERENCES "memories" ("id") ON DELETE CASCADE,
        CONSTRAINT "fk_memory_sources_conversation" FOREIGN KEY ("conversation_id")
          REFERENCES "conversations" ("id") ON DELETE CASCADE
      );
    `);
    // PK covers memory_id lookups; erasure looks up by conversation.
    await queryRunner.query(
      `CREATE INDEX "idx_memory_sources_conversation" ON "memory_sources" ("conversation_id");`,
    );

    await queryRunner.query(`
      INSERT INTO "memory_sources" ("memory_id", "conversation_id")
      SELECT m."id", c."id"
        FROM "memories" m
        JOIN "conversations" c
          ON m."source_ref" = 'conversation:' || c."id"::text
         AND c."user_id" = m."user_id"
       WHERE m."source" = 'extracted'
      ON CONFLICT DO NOTHING;
    `);
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP TABLE IF EXISTS "memory_sources";`);
  }
}
