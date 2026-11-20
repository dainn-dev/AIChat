import { NotFoundException } from '@nestjs/common';
import { DataSource } from 'typeorm';
import { ConversationErasureService } from './conversation-erasure.service';
import {
  ConversationsService,
  KeyboardCapture,
  MAX_STORED_MESSAGES_PER_CONVERSATION,
  MERGE_HISTORY_LIMIT,
} from './conversations.service';

/**
 * Android Phase 0 — keyboard capture persistence gating, merge wiring,
 * best-effort failure handling, and owner-scoped erasure, against a scripted
 * DataSource double (the DB-backed path is covered by
 * test/keyboard-memory.e2e-spec.ts).
 */
interface Scripted {
  optIn?: boolean | null; // null → user row missing
  /** Value seen by the in-transaction `FOR SHARE` re-check (defaults to optIn). */
  optInUnderLock?: boolean;
  existingConversationId?: string | null;
  storedTailDesc?: Array<{ sender: string; content: string; position: number }>;
  ownedForDelete?: boolean;
  failOn?: RegExp;
  doomedMemoryIds?: string[];
}

function build(s: Scripted = {}) {
  const calls: Array<{ sql: string; params: unknown[] }> = [];

  const answer = (sql: string, params: unknown[] = []): unknown => {
    calls.push({ sql, params });
    if (s.failOn?.test(sql)) throw new Error('db down');
    if (/SELECT memory_opt_in FROM users WHERE id = \$1 FOR SHARE/.test(sql)) {
      const v = s.optInUnderLock ?? s.optIn;
      return v === null ? [] : [{ memory_opt_in: v ?? false }];
    }
    if (/SELECT memory_opt_in FROM users/.test(sql)) {
      return s.optIn === null ? [] : [{ memory_opt_in: s.optIn ?? false }];
    }
    if (/SELECT id FROM conversations\s+WHERE user_id/.test(sql)) {
      return s.existingConversationId ? [{ id: s.existingConversationId }] : [];
    }
    if (/INSERT INTO conversations/.test(sql)) return [{ id: 'conv-new' }];
    if (/SELECT sender, content, position FROM messages/.test(sql)) {
      return s.storedTailDesc ?? [];
    }
    if (/SELECT m\.id FROM memories m/.test(sql)) {
      return (s.doomedMemoryIds ?? []).map((id) => ({ id }));
    }
    if (/FOR UPDATE/.test(sql)) {
      return s.ownedForDelete ? [{ id: params[0] }] : [];
    }
    return [];
  };

  const manager = {
    query: jest.fn(async (sql: string, params?: unknown[]) =>
      answer(sql, params),
    ),
  };
  const dataSource = {
    query: jest.fn(async (sql: string, params?: unknown[]) =>
      answer(sql, params),
    ),
    transaction: jest.fn(async (cb: (m: typeof manager) => unknown) =>
      cb(manager),
    ),
  };
  const queue = { enqueue: jest.fn().mockResolvedValue(undefined) };
  const service = new ConversationsService(
    dataSource as unknown as DataSource,
    new ConversationErasureService(),
    queue,
  );
  const insertedMessages = () => {
    const insert = calls.find((c) => /INSERT INTO messages/.test(c.sql));
    return insert ? insert.params : null;
  };
  return { service, dataSource, manager, queue, calls, insertedMessages };
}

const capture = (over: Partial<KeyboardCapture> = {}): KeyboardCapture => ({
  userId: 'user-1',
  platform: 'zalo',
  contactLabel: 'Linh',
  messages: [
    { sender: 'them', content: 'tối nay đi ăn không?' },
    { sender: 'me', content: 'ok luôn' },
  ],
  ...over,
});

describe('ConversationsService.persistKeyboardCapture — gating', () => {
  it('does not persist when the user has not opted in', async () => {
    const { service, dataSource, queue } = build({ optIn: false });
    expect(await service.persistKeyboardCapture(capture())).toBeNull();
    expect(dataSource.transaction).not.toHaveBeenCalled();
    expect(queue.enqueue).not.toHaveBeenCalled();
  });

  it('does not persist when the user row is missing', async () => {
    const { service, dataSource } = build({ optIn: null });
    expect(await service.persistKeyboardCapture(capture())).toBeNull();
    expect(dataSource.transaction).not.toHaveBeenCalled();
  });

  it.each([
    ['platform missing', { platform: undefined }],
    ['contact_label missing', { contactLabel: undefined }],
    ['remember=false', { remember: false }],
    ['only blank messages', { messages: [{ sender: 'me', content: '   ' }] }],
  ])('short-circuits without touching the DB when %s', async (_label, over) => {
    const { service, dataSource } = build({ optIn: true });
    expect(await service.persistKeyboardCapture(capture(over))).toBeNull();
    expect(dataSource.query).not.toHaveBeenCalled();
    expect(dataSource.transaction).not.toHaveBeenCalled();
  });

  it('persists when remember is omitted or true', async () => {
    for (const remember of [undefined, true]) {
      const { service } = build({ optIn: true });
      expect(await service.persistKeyboardCapture(capture({ remember }))).toBe(
        'conv-new',
      );
    }
  });
});

describe('ConversationsService.persistKeyboardCapture — opt-out race', () => {
  it('re-checks the opt-in under a user row FOR SHARE lock before touching any conversation', async () => {
    const { service, calls } = build({ optIn: true });
    await service.persistKeyboardCapture(capture());
    const order = calls.map((c) => c.sql);
    const recheck = order.findIndex((q) =>
      /FROM users WHERE id = \$1 FOR SHARE/.test(q),
    );
    expect(recheck).toBeGreaterThan(
      order.findIndex((q) => /pg_advisory_xact_lock/.test(q)),
    );
    expect(recheck).toBeLessThan(
      order.findIndex((q) => /INSERT INTO conversations/.test(q)),
    );
  });

  it('stores nothing when the user opted out after the pre-check (purge committed first)', async () => {
    const { service, calls, queue } = build({
      optIn: true,
      optInUnderLock: false,
    });
    expect(await service.persistKeyboardCapture(capture())).toBeNull();
    expect(calls.some((c) => /INSERT INTO/.test(c.sql))).toBe(false);
    expect(calls.some((c) => /FROM conversations/.test(c.sql))).toBe(false);
    expect(queue.enqueue).not.toHaveBeenCalled();
  });
});

describe('ConversationsService.persistKeyboardCapture — writes', () => {
  it('creates the conversation, appends all messages from position 0, enqueues extraction', async () => {
    const { service, queue, calls, insertedMessages } = build({ optIn: true });

    const id = await service.persistKeyboardCapture(capture());

    expect(id).toBe('conv-new');
    expect(calls.some((c) => /pg_advisory_xact_lock/.test(c.sql))).toBe(true);
    const create = calls.find((c) => /INSERT INTO conversations/.test(c.sql));
    expect(create?.params).toEqual(['user-1', 'zalo', 'Linh']);
    expect(insertedMessages()).toEqual([
      'conv-new',
      'them',
      'tối nay đi ăn không?',
      0,
      'conv-new',
      'me',
      'ok luôn',
      1,
    ]);
    expect(
      calls.find((c) => /INSERT INTO messages/.test(c.sql))?.sql,
    ).toContain("'keyboard'");
    expect(queue.enqueue).toHaveBeenCalledWith('conv-new');
  });

  it("only reuses keyboard-created conversations and creates new ones with source='keyboard'", async () => {
    const { service, calls } = build({ optIn: true });
    await service.persistKeyboardCapture(capture());
    const lookup = calls.find((c) =>
      /SELECT id FROM conversations\s+WHERE user_id/.test(c.sql),
    );
    expect(lookup?.sql).toMatch(/source = 'keyboard'/);
    const create = calls.find((c) => /INSERT INTO conversations/.test(c.sql));
    expect(create?.sql).toMatch(/'keyboard'/);
  });

  it('appends only the non-overlapping remainder with increasing positions', async () => {
    const { service, insertedMessages, queue } = build({
      optIn: true,
      existingConversationId: 'conv-1',
      // DESC order, as returned by the tail query.
      storedTailDesc: [
        { sender: 'me', content: 'ok luôn', position: 7 },
        { sender: 'them', content: 'tối nay đi ăn không?', position: 6 },
      ],
    });

    const id = await service.persistKeyboardCapture(
      capture({
        messages: [
          { sender: 'them', content: 'tối nay đi ăn không?' },
          { sender: 'me', content: '  ok luôn ' }, // trimmed before matching
          { sender: 'them', content: '7h nhé' },
        ],
      }),
    );

    expect(id).toBe('conv-1');
    expect(insertedMessages()).toEqual(['conv-1', 'them', '7h nhé', 8]);
    expect(queue.enqueue).toHaveBeenCalledWith('conv-1');
  });

  it('writes nothing and skips extraction when the capture is fully stored', async () => {
    const { service, insertedMessages, queue, calls } = build({
      optIn: true,
      existingConversationId: 'conv-1',
      storedTailDesc: [
        { sender: 'me', content: 'ok luôn', position: 1 },
        { sender: 'them', content: 'tối nay đi ăn không?', position: 0 },
      ],
    });

    expect(await service.persistKeyboardCapture(capture())).toBe('conv-1');
    expect(insertedMessages()).toBeNull();
    expect(calls.some((c) => /UPDATE conversations/.test(c.sql))).toBe(false);
    expect(queue.enqueue).not.toHaveBeenCalled();
  });

  it('compares against a bounded window of recent history', async () => {
    const { service, calls } = build({ optIn: true });
    await service.persistKeyboardCapture(capture());
    const history = calls.find((c) =>
      /SELECT sender, content, position FROM messages/.test(c.sql),
    );
    expect(history?.params).toEqual(['conv-new', MERGE_HISTORY_LIMIT]);
  });

  it('appends nothing for a scrolled-up window already in stored history', async () => {
    const { service, insertedMessages, queue } = build({
      optIn: true,
      existingConversationId: 'conv-1',
      storedTailDesc: [
        { sender: 'them', content: 'latest', position: 4 },
        { sender: 'me', content: 'c', position: 3 },
        { sender: 'them', content: 'b', position: 2 },
        { sender: 'me', content: 'a', position: 1 },
        { sender: 'them', content: 'start', position: 0 },
      ],
    });
    const id = await service.persistKeyboardCapture(
      capture({
        messages: [
          { sender: 'me', content: 'a' },
          { sender: 'them', content: 'b' },
          { sender: 'me', content: 'c' },
        ],
      }),
    );
    expect(id).toBe('conv-1');
    expect(insertedMessages()).toBeNull();
    expect(queue.enqueue).not.toHaveBeenCalled();
  });
});

describe('ConversationsService.persistKeyboardCapture — best effort', () => {
  it.each([
    ['the opt-in lookup', /SELECT memory_opt_in/],
    ['the message insert', /INSERT INTO messages/],
    ['the advisory lock', /pg_advisory_xact_lock/],
  ])('returns null (never throws) when %s fails', async (_label, failOn) => {
    const { service, queue } = build({ optIn: true, failOn });
    const errorSpy = jest
      .spyOn(
        (service as unknown as { logger: { error: () => void } }).logger,
        'error',
      )
      .mockImplementation(() => undefined);

    await expect(service.persistKeyboardCapture(capture())).resolves.toBeNull();
    expect(queue.enqueue).not.toHaveBeenCalled();
    expect(errorSpy).toHaveBeenCalled();
  });

  it('logs only the pg code/constraint, never the message, detail or stack (security L3)', async () => {
    const { service, dataSource } = build({ optIn: true });
    const pgError = Object.assign(new Error('value "Linh" violates check'), {
      code: '23514',
      constraint: 'some_check',
      detail: 'Failing row contains (Linh, secret text)',
    });
    dataSource.transaction.mockRejectedValueOnce(pgError);
    const errorSpy = jest
      .spyOn(
        (service as unknown as { logger: { error: () => void } }).logger,
        'error',
      )
      .mockImplementation(() => undefined);

    await service.persistKeyboardCapture(capture());

    const logged = JSON.stringify(errorSpy.mock.calls);
    expect(logged).toContain('code=23514');
    expect(logged).toContain('constraint=some_check');
    expect(logged).not.toContain('Linh');
    expect(logged).not.toContain('secret text');
  });

  it('works without an extraction queue bound', async () => {
    const dataSource = build({ optIn: true }).dataSource;
    const service = new ConversationsService(
      dataSource as unknown as DataSource,
      new ConversationErasureService(),
    );
    expect(await service.persistKeyboardCapture(capture())).toBe('conv-new');
  });
});

describe('ConversationsService.remove (erasure)', () => {
  it('retries the whole delete transaction once on a deadlock (40P01)', async () => {
    const { service, dataSource } = build({ ownedForDelete: true });
    const real = dataSource.transaction.getMockImplementation()!;
    dataSource.transaction.mockImplementationOnce(async () => {
      throw Object.assign(new Error('deadlock'), { code: '40P01' });
    });
    dataSource.transaction.mockImplementation(real);
    await service.remove('user-1', 'conv-1');
    expect(dataSource.transaction).toHaveBeenCalledTimes(2);
  });

  it('404s when the conversation is missing or owned by someone else', async () => {
    const { service, calls } = build({ ownedForDelete: false });
    await expect(service.remove('user-2', 'conv-1')).rejects.toBeInstanceOf(
      NotFoundException,
    );
    const lookup = calls.find((c) => /FOR UPDATE/.test(c.sql));
    expect(lookup?.params).toEqual(['conv-1', 'user-2']);
    expect(calls.some((c) => /DELETE/.test(c.sql))).toBe(false);
  });

  it('removes extracted memories, screenshots, then the conversation — all owner-scoped', async () => {
    const { service, calls } = build({
      ownedForDelete: true,
      doomedMemoryIds: ['m1', 'm2'],
    });
    await service.remove('user-1', 'conv-1');

    const select = calls.find((c) =>
      /SELECT m\.id FROM memories m/.test(c.sql),
    );
    expect(select?.params).toEqual([
      'user-1',
      ['conv-1'],
      ['conversation:conv-1'],
    ]);
    const deletes = calls.filter((c) => /DELETE FROM/.test(c.sql));
    expect(deletes.map((d) => d.sql.match(/DELETE FROM (\w+)/)?.[1])).toEqual([
      'memories',
      'screenshots',
      'conversations',
    ]);
    expect(deletes[0].params).toEqual(['user-1', ['m1', 'm2']]);
    expect(deletes[1].params).toEqual(['user-1', ['conv-1']]);
    expect(deletes[2].params).toEqual(['user-1', ['conv-1']]);
  });

  it('only selects extracted memories with no other remaining source', async () => {
    const { service, calls } = build({ ownedForDelete: true });
    await service.remove('user-1', 'conv-1');
    const sql = calls.find((c) =>
      /SELECT m\.id FROM memories m/.test(c.sql),
    )!.sql;
    expect(sql).toMatch(/m\.source = 'extracted'/);
    expect(sql).toMatch(
      /NOT EXISTS \(SELECT 1 FROM memory_sources s\s+WHERE s\.memory_id = m\.id\s+AND NOT \(s\.conversation_id = ANY\(\$2::uuid\[\]\)\)\)/,
    );
  });

  it('skips the memory delete when nothing is left unsourced', async () => {
    const { service, calls } = build({ ownedForDelete: true });
    await service.remove('user-1', 'conv-1');
    expect(calls.some((c) => /DELETE FROM memories/.test(c.sql))).toBe(false);
  });

  it('reactivates facts superseded by an erased memory, before deleting it (review MEDIUM 2)', async () => {
    const { service, calls } = build({
      ownedForDelete: true,
      doomedMemoryIds: ['m1'],
    });
    await service.remove('user-1', 'conv-1');
    const order = calls.map((c) => c.sql);
    const reactivate = order.findIndex((s) => /SET status = 'active'/.test(s));
    expect(reactivate).toBeGreaterThanOrEqual(0);
    expect(calls[reactivate].sql).toMatch(
      /superseded_by = ANY\(\$2::uuid\[\]\)/,
    );
    expect(calls[reactivate].params).toEqual(['user-1', ['m1']]);
    expect(reactivate).toBeLessThan(
      order.findIndex((s) => /DELETE FROM memories/.test(s)),
    );
  });

  it('re-points surviving memories whose source_ref named the erased conversation', async () => {
    const { service, calls } = build({ ownedForDelete: true });
    await service.remove('user-1', 'conv-1');
    const update = calls.find((c) => /SET source_ref/.test(c.sql));
    expect(update?.params).toEqual(['user-1', ['conversation:conv-1']]);
    // Runs after the conversation (and so its links) is gone.
    const order = calls.map((c) => c.sql);
    expect(order.findIndex((s) => /SET source_ref/.test(s))).toBeGreaterThan(
      order.findIndex((s) => /DELETE FROM conversations/.test(s)),
    );
  });
});

describe('ConversationsService.list (security H1)', () => {
  const make = (pages: unknown[][]) => {
    const query = jest.fn();
    pages.forEach((p) => query.mockResolvedValueOnce(p));
    const service = new ConversationsService(
      { query } as unknown as DataSource,
      new ConversationErasureService(),
    );
    return { service, query };
  };

  it('is owner-scoped, paginated, metadata-only and newest first', async () => {
    const { service, query } = make([
      [
        {
          id: 'c1',
          platform: 'zalo',
          contact_label: 'Linh',
          source: 'keyboard',
          message_count: '3',
          updated_at: new Date('2026-11-20T00:00:00Z'),
          total: '7',
        },
      ],
    ]);
    const res = await service.list('user-1', { limit: 1, offset: 2 });

    const [sql, params] = query.mock.calls[0];
    expect(params).toEqual(['user-1', 1, 2]);
    expect(sql).toMatch(/WHERE c\.user_id = \$1/);
    expect(sql).toMatch(/ORDER BY c\.updated_at DESC/);
    expect(sql).not.toMatch(/\bcontent\b/);
    expect(res).toEqual({
      conversations: [
        {
          id: 'c1',
          platform: 'zalo',
          contact_label: 'Linh',
          source: 'keyboard',
          message_count: 3,
          updated_at: '2026-11-20T00:00:00.000Z',
        },
      ],
      total: 7,
      limit: 1,
      offset: 2,
    });
  });

  it('defaults to limit 20 / offset 0 and still reports total past the last page', async () => {
    const { service, query } = make([[], [{ total: '4' }]]);
    const res = await service.list('user-1', {});
    expect(query.mock.calls[0][1]).toEqual(['user-1', 20, 0]);
    expect(query.mock.calls[1][1]).toEqual(['user-1']);
    expect(res).toEqual({ conversations: [], total: 4, limit: 20, offset: 0 });
  });
});

describe('ConversationsService rolling retention cap (security M4)', () => {
  const trimCall = (calls: Array<{ sql: string; params: unknown[] }>) =>
    calls.find((c) =>
      /FROM messages WHERE conversation_id = \$1 AND position <=/.test(c.sql),
    );

  it('drops messages older than the newest MAX_STORED_MESSAGES_PER_CONVERSATION', async () => {
    const { service, calls } = build({
      optIn: true,
      existingConversationId: 'conv-1',
      storedTailDesc: [
        {
          sender: 'me',
          content: 'last',
          position: MAX_STORED_MESSAGES_PER_CONVERSATION - 1,
        },
      ],
    });
    await service.persistKeyboardCapture(
      capture({
        messages: [
          { sender: 'me', content: 'last' },
          { sender: 'them', content: 'new' },
        ],
      }),
    );
    expect(trimCall(calls)?.params).toEqual(['conv-1', 0]);
  });

  it('does not trim below the cap', async () => {
    const { service, calls } = build({ optIn: true });
    await service.persistKeyboardCapture(capture());
    expect(trimCall(calls)).toBeUndefined();
  });
});
