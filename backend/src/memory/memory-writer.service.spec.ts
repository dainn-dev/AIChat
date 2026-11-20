import { ConfigService } from '@nestjs/config';
import { DataSource } from 'typeorm';
import { EmbeddingService } from '../ai/embedding/embedding.service';
import { ExtractedFact } from './extraction/extraction-provider.interface';
import { MemoryWriterService } from './memory-writer.service';

/**
 * Android Phase 0 — the writer's in-transaction source guard (erasure /
 * opt-out race) and `memory_sources` provenance, against a scripted manager.
 */
interface Script {
  guard: Array<{ source: string; memory_opt_in: boolean }>;
  insertReturnsId?: boolean; // false → dedupe conflict
  existingId?: string;
}

function build(script: Script) {
  const sqls: Array<{ sql: string; params: unknown[] }> = [];
  const manager = {
    query: jest.fn(async (sql: string, params: unknown[] = []) => {
      sqls.push({ sql, params });
      if (/FOR SHARE OF c/.test(sql)) return script.guard;
      if (/INSERT INTO memories/.test(sql)) {
        return script.insertReturnsId === false ? [] : [{ id: 'mem-new' }];
      }
      if (/SELECT id FROM memories/.test(sql)) {
        return script.existingId ? [{ id: script.existingId }] : [];
      }
      if (/UPDATE memories/.test(sql)) return [[], 0];
      return [];
    }),
  };
  const dataSource = {
    transaction: jest.fn(async (cb: (m: typeof manager) => unknown) =>
      cb(manager),
    ),
  } as unknown as DataSource;
  const embeddings = {
    embedOrNull: jest.fn().mockResolvedValue(null),
  } as unknown as EmbeddingService;
  const config = {
    get: () => ({ highConfidenceThreshold: 0.7 }),
  } as unknown as ConfigService;
  const writer = new MemoryWriterService(dataSource, embeddings, config);
  return { writer, sqls };
}

const fact: ExtractedFact = {
  kind: 'interest',
  content: 'Loves hiking',
  confidence: 0.9,
  scope: 'contact',
} as ExtractedFact;

const params = {
  userId: 'user-1',
  contactLabel: 'Linh',
  facts: [fact],
  sourceRef: 'conversation:conv-1',
  conversationId: 'conv-1',
};

describe('MemoryWriterService (Android Phase 0)', () => {
  it('re-checks the source conversation FOR SHARE, owner-scoped, inside the transaction', async () => {
    const { writer, sqls } = build({
      guard: [{ source: 'ocr', memory_opt_in: false }],
    });
    await writer.writeFacts(params);
    expect(sqls[0].sql).toMatch(/FOR SHARE OF c/);
    expect(sqls[0].params).toEqual(['conv-1', 'user-1']);
  });

  it('drops the write when the conversation was erased meanwhile', async () => {
    const { writer, sqls } = build({ guard: [] });
    const res = await writer.writeFacts(params);
    expect(res).toEqual({
      written: 0,
      superseded: 0,
      aborted: 'conversation-gone',
    });
    expect(sqls.some((q) => /INSERT INTO memories/.test(q.sql))).toBe(false);
  });

  it('drops the write for a keyboard conversation whose owner opted out', async () => {
    const { writer, sqls } = build({
      guard: [{ source: 'keyboard', memory_opt_in: false }],
    });
    const res = await writer.writeFacts(params);
    expect(res.aborted).toBe('opted-out');
    expect(sqls.some((q) => /INSERT INTO memories/.test(q.sql))).toBe(false);
  });

  it('does not apply the opt-in gate to non-keyboard conversations', async () => {
    const { writer } = build({
      guard: [{ source: 'ocr', memory_opt_in: false }],
    });
    const res = await writer.writeFacts(params);
    expect(res.aborted).toBeUndefined();
    expect(res.written).toBe(1);
  });

  it('links a newly written memory to its source conversation', async () => {
    const { writer, sqls } = build({
      guard: [{ source: 'keyboard', memory_opt_in: true }],
    });
    await writer.writeFacts(params);
    const link = sqls.find((q) => /INSERT INTO memory_sources/.test(q.sql));
    expect(link?.params).toEqual(['mem-new', 'conv-1']);
    expect(link?.sql).toMatch(/ON CONFLICT DO NOTHING/);
  });

  it('links the EXISTING memory when the fact deduplicates into it', async () => {
    const { writer, sqls } = build({
      guard: [{ source: 'ocr', memory_opt_in: false }],
      insertReturnsId: false,
      existingId: 'mem-old',
    });
    const res = await writer.writeFacts(params);
    expect(res.written).toBe(0);
    const lookup = sqls.find((q) => /SELECT id FROM memories/.test(q.sql));
    expect(lookup?.sql).toMatch(/contact_label IS NOT DISTINCT FROM \$2/);
    const link = sqls.find((q) => /INSERT INTO memory_sources/.test(q.sql));
    expect(link?.params).toEqual(['mem-old', 'conv-1']);
  });

  it('writes nothing (and opens no transaction) for zero facts', async () => {
    const { writer, sqls } = build({ guard: [] });
    expect(await writer.writeFacts({ ...params, facts: [] })).toEqual({
      written: 0,
      superseded: 0,
    });
    expect(sqls).toHaveLength(0);
  });
});
