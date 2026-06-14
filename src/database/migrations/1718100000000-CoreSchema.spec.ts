import { QueryRunner } from 'typeorm';
import { CoreSchema1718100000000 } from './1718100000000-CoreSchema';

/**
 * These tests assert the DDL the migration emits against a recording
 * QueryRunner. They don't need a live database — CI applies the real migration
 * against pgvector:pg16 — but they pin the acceptance criteria for WS-2:
 * every core table is created and `memories.embedding` is a nullable pgvector
 * column with no hardcoded dimension. `usage_counters` is owned by
 * CreateUsageCounters1718000100000 (WS-6), so it is intentionally absent here.
 */
describe('CoreSchema1718100000000', () => {
  const runUp = async () => {
    const queries: string[] = [];
    const queryRunner = {
      query: jest.fn((sql: string) => {
        queries.push(sql);
        return Promise.resolve();
      }),
    } as unknown as QueryRunner;

    await new CoreSchema1718100000000().up(queryRunner);
    return queries.join('\n');
  };

  const TABLES = [
    'users',
    'auth_sessions',
    'conversations',
    'messages',
    'ai_requests',
    'screenshots',
    'memories',
  ];

  it('creates every core Phase-1 table', async () => {
    const sql = await runUp();
    for (const table of TABLES) {
      expect(sql).toContain(`CREATE TABLE "${table}"`);
    }
  });

  it('does not create usage_counters (owned by CreateUsageCounters)', async () => {
    const sql = await runUp();
    expect(sql).not.toContain('CREATE TABLE "usage_counters"');
  });

  it('declares memories.embedding as a nullable, dimensionless pgvector column', async () => {
    const sql = await runUp();
    // Unbounded `vector` — dimension is deferred to the Phase-2 embedding model.
    expect(sql).toMatch(/"embedding" vector\b/);
    expect(sql).not.toMatch(/"embedding" vector\(/);
    // Nullable means no NOT NULL right after the column declaration.
    expect(sql).not.toMatch(/"embedding" vector\s+NOT NULL/);
  });

  it('enforces a unique email on users', async () => {
    const sql = await runUp();
    expect(sql).toContain('UNIQUE ("email")');
  });

  it('wires foreign keys from every child table back to users/conversations', async () => {
    const sql = await runUp();
    expect(sql).toContain('"fk_auth_sessions_user"');
    expect(sql).toContain('"fk_conversations_user"');
    expect(sql).toContain('"fk_messages_conversation"');
    expect(sql).toContain('"fk_ai_requests_user"');
    expect(sql).toContain('"fk_screenshots_user"');
    expect(sql).toContain('"fk_memories_user"');
  });

  it('reverses cleanly: down drops every table it created', async () => {
    const queries: string[] = [];
    const queryRunner = {
      query: jest.fn((sql: string) => {
        queries.push(sql);
        return Promise.resolve();
      }),
    } as unknown as QueryRunner;

    await new CoreSchema1718100000000().down(queryRunner);
    const sql = queries.join('\n');
    for (const table of TABLES) {
      expect(sql).toContain(`DROP TABLE IF EXISTS "${table}"`);
    }
  });
});
