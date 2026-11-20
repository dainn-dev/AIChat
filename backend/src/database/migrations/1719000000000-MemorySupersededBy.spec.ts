import { QueryRunner } from 'typeorm';
import { MemorySupersededBy1719000000000 } from './1719000000000-MemorySupersededBy';

/** DDL assertions for the Android Phase 0 superseded_by migration. */
describe('MemorySupersededBy1719000000000', () => {
  const record = async (direction: 'up' | 'down'): Promise<string> => {
    const queries: string[] = [];
    const queryRunner = {
      query: jest.fn((sql: string) => {
        queries.push(sql);
        return Promise.resolve();
      }),
    } as unknown as QueryRunner;
    await new MemorySupersededBy1719000000000()[direction](queryRunner);
    return queries.join('\n');
  };

  it('adds a nullable superseded_by uuid without a foreign key', async () => {
    const sql = await record('up');
    expect(sql).toMatch(/ADD COLUMN "superseded_by" uuid;/);
    expect(sql).not.toMatch(/REFERENCES/);
  });

  it('adds a partial index on superseded_by', async () => {
    expect(await record('up')).toMatch(
      /ON "memories" \("superseded_by"\)\s+WHERE "superseded_by" IS NOT NULL/,
    );
  });

  it('down drops the index and the column', async () => {
    const sql = await record('down');
    expect(sql).toMatch(/DROP INDEX IF EXISTS "idx_memories_superseded_by"/);
    expect(sql).toMatch(/DROP COLUMN IF EXISTS "superseded_by"/);
  });
});
