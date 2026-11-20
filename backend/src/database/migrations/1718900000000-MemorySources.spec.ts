import { QueryRunner } from 'typeorm';
import { MemorySources1718900000000 } from './1718900000000-MemorySources';

/** DDL/backfill assertions for the Android Phase 0 memory_sources migration. */
describe('MemorySources1718900000000', () => {
  const record = async (direction: 'up' | 'down'): Promise<string> => {
    const queries: string[] = [];
    const queryRunner = {
      query: jest.fn((sql: string) => {
        queries.push(sql);
        return Promise.resolve();
      }),
    } as unknown as QueryRunner;
    await new MemorySources1718900000000()[direction](queryRunner);
    return queries.join('\n');
  };

  it('creates the join table with a unique (memory, conversation) pair', async () => {
    expect(await record('up')).toMatch(
      /PRIMARY KEY \("memory_id", "conversation_id"\)/,
    );
  });

  it('cascades deletes from both memories and conversations', async () => {
    const sql = await record('up');
    expect(sql).toMatch(
      /FOREIGN KEY \("memory_id"\)\s+REFERENCES "memories" \("id"\) ON DELETE CASCADE/,
    );
    expect(sql).toMatch(
      /FOREIGN KEY \("conversation_id"\)\s+REFERENCES "conversations" \("id"\) ON DELETE CASCADE/,
    );
  });

  it('indexes conversation_id for erasure lookups', async () => {
    expect(await record('up')).toMatch(
      /CREATE INDEX "idx_memory_sources_conversation" ON "memory_sources" \("conversation_id"\)/,
    );
  });

  it('backfills from source_ref, owner-matched, extracted memories only', async () => {
    const sql = await record('up');
    expect(sql).toMatch(/'conversation:' \|\| c\."id"::text/);
    expect(sql).toMatch(/c\."user_id" = m\."user_id"/);
    expect(sql).toMatch(/m\."source" = 'extracted'/);
  });

  it('down drops the table', async () => {
    expect(await record('down')).toMatch(
      /DROP TABLE IF EXISTS "memory_sources"/,
    );
  });
});
