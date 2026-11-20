import { EntityManager } from 'typeorm';
import {
  ConversationErasureService,
  PURGE_BATCH_SIZE,
  PURGE_LOCK_TIMEOUT_MS,
  PURGE_STATEMENT_TIMEOUT_MS,
} from './conversation-erasure.service';

/**
 * Statement shape of the keyboard purge against a scripted manager. The
 * DB-backed semantics (cross-batch memories, re-pointing) are covered by
 * test/keyboard-memory.e2e-spec.ts.
 */
function scripted(keyboardIds: string[]) {
  const sqls: string[] = [];
  const manager = {
    query: jest.fn(async (sql: string) => {
      sqls.push(sql);
      if (/source = 'keyboard'/.test(sql) && /FOR UPDATE/.test(sql)) {
        return keyboardIds.map((id) => ({ id }));
      }
      return [];
    }),
  };
  return { manager: manager as unknown as EntityManager, sqls };
}

const ids = (n: number) =>
  Array.from(
    { length: n },
    (_, i) => `00000000-0000-0000-0000-${String(i).padStart(12, '0')}`,
  );

describe('ConversationErasureService', () => {
  const service = new ConversationErasureService();

  it('purge with no keyboard conversations is a single SELECT', async () => {
    const { manager, sqls } = scripted([]);
    await expect(
      service.purgeKeyboardConversations(manager, 'user-1'),
    ).resolves.toEqual({ conversations: 0, memories: 0 });
    expect(sqls).toHaveLength(1);
  });

  it('erases in batches of PURGE_BATCH_SIZE but re-points source_ref once', async () => {
    const { manager, sqls } = scripted(ids(PURGE_BATCH_SIZE + 5));
    const res = await service.purgeKeyboardConversations(manager, 'user-1');
    expect(res.conversations).toBe(PURGE_BATCH_SIZE + 5);
    const conversationDeletes = sqls.filter((s) =>
      /^DELETE FROM conversations/.test(s.trim()),
    );
    expect(conversationDeletes).toHaveLength(2);
    const repoints = sqls.filter((s) =>
      /SET source_ref = 'conversation:'/.test(s),
    );
    expect(repoints).toHaveLength(1);
    // ...and it runs after the last batch.
    expect(sqls.lastIndexOf(repoints[0])).toBe(sqls.length - 1);
  });

  it('applyPurgeTimeouts sets transaction-local lock and statement timeouts', async () => {
    const { manager, sqls } = scripted([]);
    await service.applyPurgeTimeouts(manager);
    expect(sqls).toEqual([
      `SET LOCAL lock_timeout = '${PURGE_LOCK_TIMEOUT_MS}ms'`,
      `SET LOCAL statement_timeout = '${PURGE_STATEMENT_TIMEOUT_MS}ms'`,
    ]);
  });
});
