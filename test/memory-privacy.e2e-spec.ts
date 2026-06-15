// Low per-user extraction budget so the shedding path is exercised. Must be set
// before AppModule is imported/instantiated (config reads it at construction).
process.env.MEMORY_EXTRACTION_DAILY_BUDGET = '2';

import { INestApplication } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import { DataSource } from 'typeorm';
import { AppModule } from '../src/app.module';
import { MemoryExtractionService } from '../src/memory/memory-extraction.service';
import { MemoriesService } from '../src/memories/memories.service';
import { EmbeddingService } from '../src/ai/embedding/embedding.service';
import {
  MEMORY_RETRIEVER,
  MemoryRetriever,
} from '../src/ai/memory/memory-retriever.interface';

/**
 * End-to-end QA of MS-6 (DAI-151) privacy/cost controls against a real DB:
 *  - Erasure: a deleted memory is provably absent from the next retrieval AND
 *    gone from the table (AC-D3, true right-to-erasure §5.7).
 *  - Sensitive filter (§5.7): health/financial/minor facts are dropped at
 *    extraction; inline PII (phone) is redacted before storage.
 *  - Cost control (§5.10): per-user extraction budget sheds over-budget work
 *    instead of running unbounded (budget forced to 2 above).
 */
describe('Memory privacy & cost controls MS-6 (e2e)', () => {
  let app: INestApplication;
  let ds: DataSource;
  let extraction: MemoryExtractionService;
  let memories: MemoriesService;
  let embeddings: EmbeddingService;
  let retriever: MemoryRetriever;
  const userIds: string[] = [];

  const createUser = async (): Promise<string> => {
    const rows: Array<{ id: string }> = await ds.query(
      `INSERT INTO users (email, password_hash) VALUES ($1, 'x') RETURNING id`,
      [`ms6-${Date.now()}-${Math.random()}@example.com`],
    );
    userIds.push(rows[0].id);
    return rows[0].id;
  };

  const createConversation = async (
    userId: string,
    messages: Array<{ sender: string; content: string }>,
  ): Promise<string> => {
    const rows: Array<{ id: string }> = await ds.query(
      `INSERT INTO conversations (user_id, platform, contact_label)
       VALUES ($1, 'whatsapp', 'Alex') RETURNING id`,
      [userId],
    );
    const convId = rows[0].id;
    for (let i = 0; i < messages.length; i++) {
      await ds.query(
        `INSERT INTO messages (conversation_id, sender, content, position, source)
         VALUES ($1, $2, $3, $4, 'app')`,
        [convId, messages[i].sender, messages[i].content, i],
      );
    }
    return convId;
  };

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = moduleRef.createNestApplication();
    await app.init();
    ds = app.get(DataSource);
    extraction = app.get(MemoryExtractionService);
    memories = app.get(MemoriesService);
    embeddings = app.get(EmbeddingService);
    retriever = app.get<MemoryRetriever>(MEMORY_RETRIEVER);
  });

  afterAll(async () => {
    if (userIds.length) {
      await ds.query(`DELETE FROM users WHERE id = ANY($1::uuid[])`, [userIds]);
    }
    await app?.close();
  });

  it('§5.7: erasure removes a memory from the next retrieval and the table', async () => {
    const userId = await createUser();
    const content = 'Plays bass in a jazz trio on Fridays';
    const vec = await embeddings.embed(content);
    const rows: Array<{ id: string }> = await ds.query(
      `INSERT INTO memories (user_id, kind, content, embedding, source_ref, status, confidence, content_hash)
       VALUES ($1, 'interest', $2, $3::vector, 'manual', 'active', 1.0, $4) RETURNING id`,
      [userId, content, `[${vec.join(',')}]`, `ms6-${Math.random()}`],
    );
    const id = rows[0].id;

    expect(
      (
        await retriever.retrieve({ userId, conversationText: content, topK: 5 })
      ).map((m) => m.content),
    ).toContain(content);

    await memories.remove(userId, id);

    expect(
      (
        await retriever.retrieve({ userId, conversationText: content, topK: 5 })
      ).map((m) => m.content),
    ).not.toContain(content);
    const left = await ds.query(`SELECT id FROM memories WHERE id = $1`, [id]);
    expect(left).toHaveLength(0);
  });

  it('§5.7: drops sensitive facts and redacts PII at extraction', async () => {
    const userId = await createUser();
    const convId = await createConversation(userId, [
      { sender: 'them', content: 'I work as a nurse, call me at 555-987-6543' },
      { sender: 'them', content: 'I have a cancer diagnosis' },
    ]);

    const res = await extraction.extractForConversation(convId);
    expect(res.status).toBe('extracted');

    const stored: Array<{ kind: string; content: string }> = await ds.query(
      `SELECT kind, content FROM memories WHERE user_id = $1`,
      [userId],
    );
    // The health fact was dropped; only the (redacted) job remains.
    expect(stored).toHaveLength(1);
    expect(stored[0].kind).toBe('job');
    expect(stored[0].content).toContain('[redacted-phone]');
    expect(stored[0].content).not.toContain('555-987-6543');
    expect(stored.some((m) => /cancer/i.test(m.content))).toBe(false);
  });

  it('§5.10: extraction respects a per-user daily budget (over-budget is shed)', async () => {
    const userId = await createUser();
    const conv = (n: number) =>
      createConversation(userId, [
        { sender: 'them', content: `I work as a chef number ${n} downtown` },
      ]);

    const first = await extraction.extractForConversation(await conv(1));
    const second = await extraction.extractForConversation(await conv(2));
    const third = await extraction.extractForConversation(await conv(3));

    expect(first.status).toBe('extracted');
    expect(second.status).toBe('extracted');
    // Budget = 2 → the third fresh conversation is shed, not run.
    expect(third.status).toBe('budget-exceeded');
    expect(third.factsWritten).toBe(0);

    const runs = await ds.query(
      `SELECT count(*)::int AS c FROM memory_extractions WHERE user_id = $1`,
      [userId],
    );
    expect(runs[0].c).toBe(2); // only the two budgeted runs were claimed
  });
});
