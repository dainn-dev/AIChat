import { INestApplication } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import { DataSource } from 'typeorm';
import { AppModule } from '../src/app.module';
import { MemoryExtractionService } from '../src/memory/memory-extraction.service';
import {
  MEMORY_RETRIEVER,
  MemoryRetriever,
} from '../src/ai/memory/memory-retriever.interface';

/**
 * End-to-end QA of the MS-4 extraction worker logic (DAI-149) against a real DB.
 * Runs with extraction *disabled* (no Redis): the queue/worker are not wired,
 * but the extraction service + writer + retriever are, so we drive
 * `extractForConversation` directly — the exact code the BullMQ worker runs.
 *
 *  - AC-M1: durable facts (job + interest) are extracted and become active.
 *  - AC-M2: re-running an unchanged conversation is idempotent (no new rows).
 *  - AC-M3: thin/emoji input produces no facts.
 *  - §5.3 supersede: a new contradictory singular fact retires the old one.
 *  - §5.9 routing + MS-3: low-confidence facts go pending_review and are NOT
 *    retrievable; active facts are.
 */
describe('Memory extraction MS-4 (e2e)', () => {
  let app: INestApplication;
  let ds: DataSource;
  let extraction: MemoryExtractionService;
  let retriever: MemoryRetriever;
  const userIds: string[] = [];

  const createUser = async (): Promise<string> => {
    const rows: Array<{ id: string }> = await ds.query(
      `INSERT INTO users (email, password_hash) VALUES ($1, 'x') RETURNING id`,
      [`ms4-${Date.now()}-${Math.random()}@example.com`],
    );
    userIds.push(rows[0].id);
    return rows[0].id;
  };

  const createConversation = async (
    userId: string,
    contactLabel: string,
    messages: Array<{ sender: string; content: string }>,
  ): Promise<string> => {
    const rows: Array<{ id: string }> = await ds.query(
      `INSERT INTO conversations (user_id, platform, contact_label)
       VALUES ($1, 'whatsapp', $2) RETURNING id`,
      [userId, contactLabel],
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

  const activeMemories = (userId: string) =>
    ds.query(
      `SELECT kind, content, status, confidence, contact_label
         FROM memories WHERE user_id = $1 ORDER BY created_at`,
      [userId],
    );

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = moduleRef.createNestApplication();
    await app.init();
    ds = app.get(DataSource);
    extraction = app.get(MemoryExtractionService);
    retriever = app.get<MemoryRetriever>(MEMORY_RETRIEVER);
  });

  afterAll(async () => {
    if (userIds.length) {
      await ds.query(`DELETE FROM users WHERE id = ANY($1::uuid[])`, [userIds]);
    }
    await app?.close();
  });

  it('AC-M1 + AC-M2: extracts durable facts, then is idempotent on re-run', async () => {
    const userId = await createUser();
    const convId = await createConversation(userId, 'Alex', [
      { sender: 'them', content: 'I work as a chef at a bistro downtown' },
      { sender: 'them', content: 'I love hiking on the weekends' },
      { sender: 'me', content: 'cool, talk later' },
    ]);

    const first = await extraction.extractForConversation(convId);
    expect(first.status).toBe('extracted');
    expect(first.factsWritten).toBeGreaterThanOrEqual(2);

    const rows = await activeMemories(userId);
    const kinds = rows.map((r: { kind: string }) => r.kind);
    expect(kinds).toContain('job');
    expect(kinds).toContain('interest');
    expect(rows.every((r: { status: string }) => r.status === 'active')).toBe(
      true,
    );

    // AC-M2: re-running an unchanged conversation is idempotent — the
    // content_hash dedupe index writes no new rows (and supersedes nothing).
    const again = await extraction.extractForConversation(convId);
    expect(again.status).toBe('extracted');
    expect(again.factsWritten).toBe(0);
    const rowsAfter = await activeMemories(userId);
    expect(rowsAfter).toHaveLength(rows.length);
  });

  it('AC-M3: thin / emoji input writes no facts', async () => {
    const userId = await createUser();
    const convId = await createConversation(userId, 'Sam', [
      { sender: 'them', content: '😀😀' },
      { sender: 'me', content: 'ok' },
      { sender: 'them', content: 'lol' },
    ]);

    const res = await extraction.extractForConversation(convId);
    expect(res.factsWritten).toBe(0);
    expect(await activeMemories(userId)).toHaveLength(0);
  });

  it('§5.9 + MS-3: low-confidence facts go pending_review and are not retrievable', async () => {
    const userId = await createUser();
    const convId = await createConversation(userId, 'Alex', [
      { sender: 'them', content: 'I work as a chef at a bistro downtown' },
      { sender: 'them', content: 'I have a cat named Mochi' },
    ]);
    await extraction.extractForConversation(convId);

    const rows = await activeMemories(userId);
    const job = rows.find((r: { kind: string }) => r.kind === 'job');
    const weakFact = rows.find((r: { kind: string }) => r.kind === 'fact');
    expect(job.status).toBe('active');
    expect(weakFact.status).toBe('pending_review');

    // Active job fact is retrievable…
    const hit = await retriever.retrieve({
      userId,
      contactLabel: 'Alex',
      conversationText: job.content,
      topK: 5,
    });
    expect(hit.map((m) => m.content)).toContain(job.content);

    // …but the pending fact is not surfaced by retrieval.
    const miss = await retriever.retrieve({
      userId,
      contactLabel: 'Alex',
      conversationText: weakFact.content,
      topK: 5,
    });
    expect(miss.map((m) => m.content)).not.toContain(weakFact.content);
  });

  it('§5.3: a new contradictory singular fact supersedes the old one (latest-wins)', async () => {
    const userId = await createUser();
    const conv1 = await createConversation(userId, 'Alex', [
      { sender: 'them', content: 'I work as a chef at a bistro downtown' },
    ]);
    await extraction.extractForConversation(conv1);

    const conv2 = await createConversation(userId, 'Alex', [
      { sender: 'them', content: 'I work as a teacher at the high school now' },
    ]);
    await extraction.extractForConversation(conv2);

    const jobs = (await activeMemories(userId)).filter(
      (r: { kind: string }) => r.kind === 'job',
    );
    const active = jobs.filter(
      (r: { status: string }) => r.status === 'active',
    );
    const superseded = jobs.filter(
      (r: { status: string }) => r.status === 'superseded',
    );
    expect(active).toHaveLength(1);
    expect(active[0].content.toLowerCase()).toContain('teacher');
    expect(superseded).toHaveLength(1);
    expect(superseded[0].content.toLowerCase()).toContain('chef');
  });
});
