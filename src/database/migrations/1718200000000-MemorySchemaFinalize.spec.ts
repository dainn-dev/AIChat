import { QueryRunner } from 'typeorm';
import { MemorySchemaFinalize1718200000000 } from './1718200000000-MemorySchemaFinalize';

/**
 * DDL assertions for the MS-2 migration (DAI-147) against a recording
 * QueryRunner. CI applies the real migration on pgvector:pg16; these tests pin
 * the acceptance criteria: the embedding is dimensioned for HNSW, the ANN index
 * uses `vector_cosine_ops`, the idempotency dedupe key exists, and `down`
 * reverses every object it created.
 */
describe('MemorySchemaFinalize1718200000000', () => {
  const record = async (direction: 'up' | 'down'): Promise<string> => {
    const queries: string[] = [];
    const queryRunner = {
      query: jest.fn((sql: string) => {
        queries.push(sql);
        return Promise.resolve();
      }),
    } as unknown as QueryRunner;

    const migration = new MemorySchemaFinalize1718200000000();
    await migration[direction](queryRunner);
    return queries.join('\n');
  };

  it('pins the embedding dimension to vector(1536)', async () => {
    const sql = await record('up');
    expect(sql).toMatch(/ALTER COLUMN "embedding" TYPE vector\(1536\)/);
  });

  it('keeps the embedding nullable (no NOT NULL added)', async () => {
    const sql = await record('up');
    expect(sql).not.toMatch(/"embedding"[^;]*SET NOT NULL/);
  });

  it('adds the Phase-2 memory columns with the right defaults', async () => {
    const sql = await record('up');
    expect(sql).toMatch(
      /ADD COLUMN "source" "memory_source" NOT NULL DEFAULT 'extracted'/,
    );
    expect(sql).toMatch(/ADD COLUMN "confidence" real/);
    expect(sql).toMatch(
      /ADD COLUMN "status" "memory_status" NOT NULL DEFAULT 'active'/,
    );
    expect(sql).toMatch(/ADD COLUMN "embedding_model" text/);
    expect(sql).toMatch(
      /ADD COLUMN "updated_at" timestamptz NOT NULL DEFAULT now\(\)/,
    );
    expect(sql).toMatch(/ADD COLUMN "content_hash" text/);
  });

  it('creates the source/status enum types', async () => {
    const sql = await record('up');
    expect(sql).toMatch(
      /CREATE TYPE "memory_source" AS ENUM \('extracted', 'manual'\)/,
    );
    expect(sql).toMatch(
      /CREATE TYPE "memory_status" AS ENUM \('active', 'pending_review', 'dismissed'\)/,
    );
  });

  it('builds an HNSW index on embedding using vector_cosine_ops (FR-E3)', async () => {
    const sql = await record('up');
    expect(sql).toMatch(
      /CREATE INDEX "idx_memories_embedding_hnsw"\s+ON "memories" USING hnsw \("embedding" vector_cosine_ops\)/,
    );
  });

  it('adds a partial NULLS-NOT-DISTINCT dedupe index for idempotency (FR-M5)', async () => {
    const sql = await record('up');
    expect(sql).toMatch(
      /CREATE UNIQUE INDEX "uq_memories_content_hash"\s+ON "memories" \("user_id", "contact_label", "kind", "content_hash"\)\s+NULLS NOT DISTINCT\s+WHERE "content_hash" IS NOT NULL/,
    );
  });

  it('adds the (user_id, contact_label) scope index and drops the redundant one (FR-RT2)', async () => {
    const sql = await record('up');
    expect(sql).toMatch(
      /CREATE INDEX "idx_memories_user_contact"\s+ON "memories" \("user_id", "contact_label"\)/,
    );
    expect(sql).toContain('DROP INDEX "idx_memories_user";');
  });

  it('creates the memory_extractions audit table', async () => {
    const sql = await record('up');
    expect(sql).toContain('CREATE TABLE "memory_extractions"');
    expect(sql).toContain('"n_memories" integer NOT NULL DEFAULT 0');
    expect(sql).toContain('"fk_memory_extractions_user"');
  });

  it('reverses cleanly: down restores the original index and drops new objects', async () => {
    const sql = await record('down');
    expect(sql).toContain('DROP TABLE IF EXISTS "memory_extractions";');
    expect(sql).toContain(
      'DROP INDEX IF EXISTS "idx_memories_embedding_hnsw";',
    );
    expect(sql).toContain('DROP INDEX IF EXISTS "uq_memories_content_hash";');
    expect(sql).toContain('DROP INDEX IF EXISTS "idx_memories_user_contact";');
    // Original single-column index is recreated on rollback.
    expect(sql).toMatch(
      /CREATE INDEX "idx_memories_user" ON "memories" \("user_id"\)/,
    );
    expect(sql).toMatch(/ALTER COLUMN "embedding" TYPE vector\b/);
    expect(sql).toContain('DROP TYPE IF EXISTS "memory_status";');
    expect(sql).toContain('DROP TYPE IF EXISTS "memory_source";');
  });
});
