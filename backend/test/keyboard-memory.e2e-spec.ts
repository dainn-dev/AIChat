import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { DataSource } from 'typeorm';
import { AppModule } from '../src/app.module';
import { AllExceptionsFilter } from '../src/common/filters/all-exceptions.filter';
import { MemoryExtractionService } from '../src/memory/memory-extraction.service';
import { MemoryWriterService } from '../src/memory/memory-writer.service';
import {
  ConversationErasureService,
  PURGE_BATCH_SIZE,
} from '../src/conversations/conversation-erasure.service';
import { LegacyOptOutPurgeService } from '../src/conversations/legacy-optout-purge.service';
import {
  MEMORY_RETRIEVER,
  MemoryRetriever,
} from '../src/ai/memory/memory-retriever.interface';

/**
 * Android Phase 0 against a real, migrated Postgres:
 *  - `memory_opt_in` defaults to false and is toggled via PATCH /me/settings;
 *  - keyboard captures are persisted only when opted in AND platform +
 *    contact_label are present AND remember != false;
 *  - overlapping captures merge without duplicates (source = 'keyboard');
 *  - DELETE /conversations/:id is owner-scoped (404 for others, no IDOR) and
 *    erases messages + memories sourced from the conversation;
 *  - every memory_opt_in:false purges every keyboard conversation and the
 *    memories only they backed, in the same transaction as the flag change
 *    (batched; the one-off legacy backfill uses the same purge).
 */
describe('Keyboard memory capture + erasure, Android Phase 0 (e2e)', () => {
  let app: INestApplication;
  let ds: DataSource;

  const http = () => request(app.getHttpServer());
  const auth = (t: string) => ({ Authorization: `Bearer ${t}` });

  const signup = async (): Promise<{ token: string; userId: string }> => {
    const email = `kbd-mem-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`;
    const res = await http()
      .post('/auth/signup')
      .send({ email, password: 'password123' });
    expect(res.status).toBe(201);
    return { token: res.body.access_token, userId: res.body.user.id };
  };

  const optIn = async (token: string, value: boolean) => {
    const res = await http()
      .patch('/me/settings')
      .set(auth(token))
      .send({ memory_opt_in: value });
    expect(res.status).toBe(200);
    expect(res.body.settings.memory_opt_in).toBe(value);
  };

  const reply = (token: string, body: Record<string, unknown>) =>
    http()
      .post('/v1/keyboard/reply')
      .set(auth(token))
      .send({ tone: 'Friendly', ...body });

  const storedMessages = async (conversationId: string) =>
    ds.query(
      `SELECT sender, content, position, source FROM messages
        WHERE conversation_id = $1 ORDER BY position ASC`,
      [conversationId],
    ) as Promise<
      Array<{
        sender: string;
        content: string;
        position: number;
        source: string;
      }>
    >;

  const first = [
    { sender: 'them', content: 'tối nay đi ăn không?' },
    { sender: 'me', content: 'ok luôn' },
  ];
  const second = [
    { sender: 'me', content: 'ok luôn' },
    { sender: 'them', content: '7h nhé' },
  ];

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = moduleRef.createNestApplication();
    app.useGlobalPipes(
      new ValidationPipe({
        whitelist: true,
        forbidNonWhitelisted: true,
        transform: true,
      }),
    );
    app.useGlobalFilters(new AllExceptionsFilter());
    await app.init();
    ds = app.get(DataSource);
  });

  afterAll(async () => {
    await app?.close();
  });

  it('GET /me exposes memory_opt_in=false by default; PATCH toggles it', async () => {
    const { token } = await signup();
    const me = await http().get('/me').set(auth(token));
    expect(me.status).toBe(200);
    expect(me.body.settings).toEqual({
      memory_opt_in: false,
      training_opt_in: false,
    });

    await optIn(token, true);
    const after = await http().get('/me').set(auth(token));
    expect(after.body.settings.memory_opt_in).toBe(true);
  });

  it('PATCH training_opt_in starts, keeps and clears the consent window without touching memory_opt_in', async () => {
    const { token, userId } = await signup();
    await optIn(token, true);
    const patch = (value: boolean) =>
      http()
        .patch('/me/settings')
        .set(auth(token))
        .send({ training_opt_in: value });
    const consentAt = async (): Promise<Date | null> =>
      (
        await ds.query(`SELECT training_opt_in_at FROM users WHERE id = $1`, [
          userId,
        ])
      )[0].training_opt_in_at;

    const on = await patch(true);
    expect(on.status).toBe(200);
    expect(on.body).toEqual({
      settings: { memory_opt_in: true, training_opt_in: true },
    });
    const startedAt = await consentAt();
    expect(startedAt).toBeInstanceOf(Date);

    // Turning it on again keeps the original start.
    await patch(true);
    expect(await consentAt()).toEqual(startedAt);

    const off = await patch(false);
    expect(off.body).toEqual({
      settings: { memory_opt_in: true, training_opt_in: false },
    });
    expect(off.body).not.toHaveProperty('deleted');
    expect(await consentAt()).toBeNull();
  });

  it('does not persist for a user who has not opted in', async () => {
    const { token, userId } = await signup();
    const res = await reply(token, {
      platform: 'zalo',
      contact_label: 'Linh',
      lang: 'vi',
      conversation: first,
    });
    expect(res.status).toBe(200);
    expect(res.body.conversation_id).toBeNull();
    const rows = await ds.query(
      `SELECT id FROM conversations WHERE user_id = $1`,
      [userId],
    );
    expect(rows).toHaveLength(0);
  });

  it('persists and merges overlapping captures without duplicates', async () => {
    const { token } = await signup();
    await optIn(token, true);

    const r1 = await reply(token, {
      platform: 'zalo',
      contact_label: 'Linh',
      conversation: first,
    });
    expect(r1.status).toBe(200);
    expect(r1.body.conversation_id).toEqual(expect.any(String));

    const r2 = await reply(token, {
      platform: 'zalo',
      contact_label: 'Linh',
      conversation: second,
    });
    expect(r2.body.conversation_id).toBe(r1.body.conversation_id);

    // Re-sending an already-stored window appends nothing.
    await reply(token, {
      platform: 'zalo',
      contact_label: 'Linh',
      conversation: second,
    });

    const msgs = await storedMessages(r1.body.conversation_id);
    expect(msgs.map((m) => [m.sender, m.content, m.position])).toEqual([
      ['them', 'tối nay đi ăn không?', 0],
      ['me', 'ok luôn', 1],
      ['them', '7h nhé', 2],
    ]);
    expect(msgs.every((m) => m.source === 'keyboard')).toBe(true);
  });

  it('ignores a scrolled-up window that is already stored', async () => {
    const { token } = await signup();
    await optIn(token, true);
    const full = ['1', '2', '3', '4', '5', '6'].map((n, i) => ({
      sender: i % 2 === 0 ? 'them' : 'me',
      content: `msg ${n}`,
    }));
    const r1 = await reply(token, {
      platform: 'zalo',
      contact_label: 'Scroll',
      conversation: full,
    });
    // User scrolls up: the keyboard now sees messages 2..4 only.
    const r2 = await reply(token, {
      platform: 'zalo',
      contact_label: 'Scroll',
      conversation: full.slice(1, 4),
    });
    expect(r2.body.conversation_id).toBe(r1.body.conversation_id);
    expect(await storedMessages(r1.body.conversation_id)).toHaveLength(6);
  });

  it('never merges into a screenshot/share conversation for the same contact', async () => {
    const { token } = await signup();
    await optIn(token, true);
    const shot = {
      platform: 'zalo',
      contact_label: 'Linh',
      ocr_text: 'them: tối nay đi ăn không? / me: ok luôn',
      extracted_messages: first,
    };
    const s1 = await http().post('/screenshots').set(auth(token)).send(shot);
    expect(s1.status).toBe(201);
    const s2 = await http()
      .post('/v1/share/analyze')
      .set(auth(token))
      .send(shot);
    expect(s2.status).toBe(200);

    const kb = await reply(token, {
      platform: 'zalo',
      contact_label: 'Linh',
      conversation: second,
    });
    const kbId = kb.body.conversation_id as string;
    expect(kbId).not.toBe(s1.body.conversation_id);
    expect(kbId).not.toBe(s2.body.conversation_id);

    const sources: Array<{ id: string; source: string }> = await ds.query(
      `SELECT id, source FROM conversations WHERE id = ANY($1::uuid[])`,
      [[s1.body.conversation_id, s2.body.conversation_id, kbId]],
    );
    const byId = Object.fromEntries(sources.map((r) => [r.id, r.source]));
    expect(byId[s1.body.conversation_id]).toBe('ocr');
    expect(byId[s2.body.conversation_id]).toBe('share');
    expect(byId[kbId]).toBe('keyboard');
    // Only the keyboard capture is in the keyboard conversation.
    expect(await storedMessages(kbId)).toHaveLength(2);
  });

  it('keys conversations by (platform, contact_label)', async () => {
    const { token } = await signup();
    await optIn(token, true);
    const a = await reply(token, {
      platform: 'zalo',
      contact_label: 'Linh',
      conversation: first,
    });
    const b = await reply(token, {
      platform: 'zalo',
      contact_label: 'Minh',
      conversation: first,
    });
    const c = await reply(token, {
      platform: 'messenger',
      contact_label: 'Linh',
      conversation: first,
    });
    const ids = new Set([
      a.body.conversation_id,
      b.body.conversation_id,
      c.body.conversation_id,
    ]);
    expect(ids.size).toBe(3);
  });

  it.each([
    [
      'remember=false',
      { platform: 'zalo', contact_label: 'Linh', remember: false },
    ],
    ['no contact_label', { platform: 'zalo' }],
    ['no platform', { contact_label: 'Linh' }],
  ])('does not persist with %s even when opted in', async (_label, extra) => {
    const { token, userId } = await signup();
    await optIn(token, true);
    const res = await reply(token, { conversation: first, ...extra });
    expect(res.status).toBe(200);
    expect(res.body.conversation_id).toBeNull();
    const rows = await ds.query(
      `SELECT id FROM conversations WHERE user_id = $1`,
      [userId],
    );
    expect(rows).toHaveLength(0);
  });

  describe('GET /conversations', () => {
    it('lists only the caller conversations, metadata only, so they can be erased', async () => {
      const owner = await signup();
      await optIn(owner.token, true);
      const r = await reply(owner.token, {
        platform: 'zalo',
        contact_label: 'Linh',
        conversation: first,
      });
      const id = r.body.conversation_id as string;

      const res = await http().get('/conversations').set(auth(owner.token));
      expect(res.status).toBe(200);
      expect(res.body.total).toBe(1);
      expect(res.body.conversations).toEqual([
        {
          id,
          platform: 'zalo',
          contact_label: 'Linh',
          source: 'keyboard',
          message_count: 2,
          updated_at: expect.any(String),
        },
      ]);
      expect(JSON.stringify(res.body)).not.toContain('tối nay');

      const other = await signup();
      const theirs = await http().get('/conversations').set(auth(other.token));
      expect(theirs.body).toEqual({
        conversations: [],
        total: 0,
        limit: 20,
        offset: 0,
      });

      // Find → erase round trip.
      expect(
        (await http().delete(`/conversations/${id}`).set(auth(owner.token)))
          .status,
      ).toBe(204);
      const after = await http().get('/conversations').set(auth(owner.token));
      expect(after.body.total).toBe(0);
    });

    it('paginates newest first', async () => {
      const { token } = await signup();
      await optIn(token, true);
      for (const contact of ['A', 'B', 'C']) {
        await reply(token, {
          platform: 'zalo',
          contact_label: contact,
          conversation: first,
        });
      }
      const page = await http()
        .get('/conversations?limit=2&offset=0')
        .set(auth(token));
      expect(page.body.total).toBe(3);
      expect(
        page.body.conversations.map(
          (c: { contact_label: string }) => c.contact_label,
        ),
      ).toEqual(['C', 'B']);
      const last = await http()
        .get('/conversations?limit=2&offset=2')
        .set(auth(token));
      expect(last.body.conversations).toHaveLength(1);
    });
  });

  describe('DELETE /conversations/:id', () => {
    it('404s for another user (no IDOR) and leaves the data intact', async () => {
      const owner = await signup();
      await optIn(owner.token, true);
      const r = await reply(owner.token, {
        platform: 'zalo',
        contact_label: 'Linh',
        conversation: first,
      });
      const id = r.body.conversation_id as string;

      const attacker = await signup();
      const res = await http()
        .delete(`/conversations/${id}`)
        .set(auth(attacker.token));
      expect(res.status).toBe(404);
      expect(res.body.error.code).toBe('CONVERSATION_NOT_FOUND');
      expect(await storedMessages(id)).toHaveLength(2);
    });

    it('erases the conversation, its messages and memories sourced from it', async () => {
      const { token, userId } = await signup();
      await optIn(token, true);
      const r = await reply(token, {
        platform: 'zalo',
        contact_label: 'Linh',
        conversation: first,
      });
      const id = r.body.conversation_id as string;

      // A memory extracted from this conversation (MS-4 provenance format) and
      // an unrelated one that must survive.
      await ds.query(
        `INSERT INTO memories (user_id, contact_label, kind, content, source_ref, status)
         VALUES ($1, 'Linh', 'preference', 'Likes pho', $2, 'active'),
                ($1, 'Linh', 'preference', 'Hates rain', 'manual', 'active')`,
        [userId, `conversation:${id}`],
      );

      const res = await http().delete(`/conversations/${id}`).set(auth(token));
      expect(res.status).toBe(204);

      expect(
        await ds.query(`SELECT id FROM conversations WHERE id = $1`, [id]),
      ).toHaveLength(0);
      expect(await storedMessages(id)).toHaveLength(0);
      const left: Array<{ content: string }> = await ds.query(
        `SELECT content FROM memories WHERE user_id = $1`,
        [userId],
      );
      expect(left.map((m) => m.content)).toEqual(['Hates rain']);

      const again = await http()
        .delete(`/conversations/${id}`)
        .set(auth(token));
      expect(again.status).toBe(404);
    });
  });

  describe('precise erasure via memory_sources', () => {
    const HIKING = 'I love hiking on the weekends';

    const createConversation = async (
      userId: string,
      content: string,
      source = 'app',
    ): Promise<string> => {
      const rows: Array<{ id: string }> = await ds.query(
        `INSERT INTO conversations (user_id, platform, contact_label, source)
         VALUES ($1, 'whatsapp', 'Alex', $2) RETURNING id`,
        [userId, source],
      );
      await ds.query(
        `INSERT INTO messages (conversation_id, sender, content, position, source)
         VALUES ($1, 'them', $2, 0, $3)`,
        [rows[0].id, content, source === 'keyboard' ? 'keyboard' : 'app'],
      );
      return rows[0].id;
    };

    const memoriesOf = (userId: string) =>
      ds.query(
        `SELECT m.id, m.content, m.source, m.source_ref,
                ARRAY(SELECT s.conversation_id::text FROM memory_sources s
                       WHERE s.memory_id = m.id ORDER BY s.conversation_id) AS sources
           FROM memories m WHERE m.user_id = $1 ORDER BY m.created_at`,
        [userId],
      ) as Promise<
        Array<{
          id: string;
          content: string;
          source: string;
          source_ref: string;
          sources: string[];
        }>
      >;

    it('keeps a fact while another conversation still backs it, then erases it', async () => {
      const extraction = app.get(MemoryExtractionService);
      const { token, userId } = await signup();
      const convA = await createConversation(userId, HIKING);
      const convB = await createConversation(userId, HIKING);

      await extraction.extractForConversation(convA);
      await extraction.extractForConversation(convB); // dedupes into A's row

      const [hiking] = (await memoriesOf(userId)).filter(
        (m) => m.source === 'extracted',
      );
      expect(hiking).toBeDefined();
      expect(hiking.sources).toEqual([convA, convB].sort());

      // A manual memory must never be touched by conversation erasure.
      await ds.query(
        `INSERT INTO memories (user_id, contact_label, kind, content, source, source_ref, status)
         VALUES ($1, 'Alex', 'interest', 'Collects vinyl', 'manual', 'manual', 'active')`,
        [userId],
      );

      const delA = await http()
        .delete(`/conversations/${convA}`)
        .set(auth(token));
      expect(delA.status).toBe(204);
      let left = await memoriesOf(userId);
      const survivor = left.find((m) => m.id === hiking.id);
      expect(survivor?.sources).toEqual([convB]);
      expect(survivor?.source_ref).toBe(`conversation:${convB}`);

      const delB = await http()
        .delete(`/conversations/${convB}`)
        .set(auth(token));
      expect(delB.status).toBe(204);
      left = await memoriesOf(userId);
      expect(left.find((m) => m.id === hiking.id)).toBeUndefined();
      expect(left.map((m) => m.content)).toEqual(['Collects vinyl']);
    });

    it('drops extraction results for a conversation erased mid-run (race guard)', async () => {
      const writer = app.get(MemoryWriterService);
      const { token, userId } = await signup();
      const conv = await createConversation(userId, HIKING);
      await http().delete(`/conversations/${conv}`).set(auth(token));

      // The worker had already extracted facts before the delete committed.
      const res = await writer.writeFacts({
        userId,
        contactLabel: 'Alex',
        facts: [
          {
            kind: 'interest',
            content: 'Loves hiking',
            confidence: 0.9,
            scope: 'contact',
          },
        ],
        sourceRef: `conversation:${conv}`,
        conversationId: conv,
      });
      expect(res.aborted).toBe('conversation-gone');
      expect(await memoriesOf(userId)).toHaveLength(0);
    });

    it('does not extract a keyboard conversation after its owner opts out', async () => {
      const extraction = app.get(MemoryExtractionService);
      const { token, userId } = await signup();
      await optIn(token, true);
      const r = await reply(token, {
        platform: 'zalo',
        contact_label: 'Alex',
        conversation: [{ sender: 'them', content: HIKING }],
      });
      const conv = r.body.conversation_id as string;
      expect(conv).toEqual(expect.any(String));

      // Opt-out purges the conversation, so a queued job finds nothing.
      await optIn(token, false);
      const res = await extraction.extractForConversation(conv);
      expect(res.status).toBe('no-conversation');
      expect(await memoriesOf(userId)).toHaveLength(0);

      // Defence in depth: a keyboard conversation of an opted-out user that
      // somehow exists is still never extracted.
      const stray = await createConversation(userId, HIKING, 'keyboard');
      const res2 = await extraction.extractForConversation(stray);
      expect(res2.status).toBe('opted-out');
      expect(await memoriesOf(userId)).toHaveLength(0);
    });

    it('reactivates the older fact when the memory that superseded it is erased', async () => {
      const extraction = app.get(MemoryExtractionService);
      const { token, userId } = await signup();
      const oldConv = await createConversation(
        userId,
        'I work as a chef at a bistro downtown',
      );
      const newConv = await createConversation(
        userId,
        'I work as a teacher at the high school now',
      );
      await extraction.extractForConversation(oldConv);
      await extraction.extractForConversation(newConv);

      const jobs = async () =>
        (await ds.query(
          `SELECT id, content, status, superseded_by FROM memories
            WHERE user_id = $1 AND kind = 'job' ORDER BY created_at`,
          [userId],
        )) as Array<{
          id: string;
          content: string;
          status: string;
          superseded_by: string | null;
        }>;
      const [chef, teacher] = await jobs();
      expect(chef.status).toBe('superseded');
      expect(chef.superseded_by).toBe(teacher.id);
      expect(teacher.status).toBe('active');

      const res = await http()
        .delete(`/conversations/${newConv}`)
        .set(auth(token));
      expect(res.status).toBe(204);
      const after = await jobs();
      expect(after).toHaveLength(1);
      expect(after[0]).toMatchObject({
        id: chef.id,
        status: 'active',
        superseded_by: null,
      });

      // Same via the memory dashboard: DELETE /memories/:id reactivates too.
      const againConv = await createConversation(
        userId,
        'I work as a teacher at the high school now',
      );
      await extraction.extractForConversation(againConv);
      const [chef2, teacher2] = await jobs();
      expect(chef2.status).toBe('superseded');
      const del = await http()
        .delete(`/memories/${teacher2.id}`)
        .set(auth(token));
      expect(del.status).toBe(204);
      expect((await jobs())[0]).toMatchObject({
        id: chef.id,
        status: 'active',
      });
    });

    it('M3: a fact stated by "them" never becomes an active GLOBAL memory', async () => {
      const extraction = app.get(MemoryExtractionService);
      const { userId } = await signup();
      // No contact_label → anything stored would be global (contact_label NULL).
      const rows: Array<{ id: string }> = await ds.query(
        `INSERT INTO conversations (user_id, platform, source)
         VALUES ($1, 'whatsapp', 'ocr') RETURNING id`,
        [userId],
      );
      await ds.query(
        `INSERT INTO messages (conversation_id, sender, content, position, source)
         VALUES ($1, 'them', 'I work as a chef at a bistro downtown', 0, 'ocr'),
                ($1, 'me', 'I love hiking on the weekends', 1, 'ocr')`,
        [rows[0].id],
      );
      await extraction.extractForConversation(rows[0].id);

      const stored: Array<{ content: string; status: string }> = await ds.query(
        `SELECT content, status FROM memories
          WHERE user_id = $1 AND contact_label IS NULL ORDER BY content`,
        [userId],
      );
      const byContent = Object.fromEntries(
        stored.map((m) => [m.content, m.status]),
      );
      // "them" → global → held for review; "me" → accepted.
      expect(Object.entries(byContent)).toEqual(
        expect.arrayContaining([
          [expect.stringMatching(/chef/), 'pending_review'],
          [expect.stringMatching(/hiking/), 'active'],
        ]),
      );
    });

    it('opt-out (true -> false) purges keyboard conversations and keyboard-only memories', async () => {
      const extraction = app.get(MemoryExtractionService);
      const retriever = app.get<MemoryRetriever>(MEMORY_RETRIEVER);
      const { token, userId } = await signup();
      const other = await signup();
      await optIn(token, true);
      await optIn(other.token, true);

      const CHEF = 'I work as a chef at a bistro downtown';
      // Keyboard-only fact (CHEF) and a fact shared with an OCR conversation.
      const kbChef = await createConversation(userId, CHEF, 'keyboard');
      const kbHike = await createConversation(userId, HIKING, 'keyboard');
      const ocrHike = await createConversation(userId, HIKING, 'ocr');
      const otherKb = await createConversation(
        other.userId,
        HIKING,
        'keyboard',
      );
      for (const c of [kbChef, kbHike, ocrHike, otherKb]) {
        await extraction.extractForConversation(c);
      }
      const manual = await http().post('/memories').set(auth(token)).send({
        kind: 'interest',
        content: 'Collects vinyl',
        contact_label: 'Alex',
      });
      expect(manual.status).toBe(201);

      const before = await memoriesOf(userId);
      const chefFact = before.find((m) => m.sources.includes(kbChef))!;
      const hikeFact = before.find((m) => m.sources.includes(ocrHike))!;
      expect(chefFact).toBeDefined();
      expect(hikeFact.sources).toEqual([kbHike, ocrHike].sort());
      const otherBefore = await memoriesOf(other.userId);
      expect(otherBefore).toHaveLength(1);

      const res = await http()
        .patch('/me/settings')
        .set(auth(token))
        .send({ memory_opt_in: false });
      expect(res.status).toBe(200);
      expect(res.body).toEqual({
        settings: { memory_opt_in: false, training_opt_in: false },
        deleted: { conversations: 2, memories: 1 },
      });

      const convs: Array<{ id: string }> = await ds.query(
        `SELECT id FROM conversations WHERE user_id = $1`,
        [userId],
      );
      expect(convs.map((c) => c.id)).toEqual([ocrHike]);
      const kbMsgs: Array<{ n: number }> = await ds.query(
        `SELECT count(*)::int AS n FROM messages
          WHERE conversation_id = ANY($1::uuid[])`,
        [[kbChef, kbHike]],
      );
      expect(kbMsgs[0].n).toBe(0);

      const after = await memoriesOf(userId);
      expect(after.find((m) => m.id === chefFact.id)).toBeUndefined();
      const survivor = after.find((m) => m.id === hikeFact.id);
      expect(survivor?.sources).toEqual([ocrHike]);
      expect(survivor?.source_ref).toBe(`conversation:${ocrHike}`);
      expect(after.map((m) => m.content)).toContain('Collects vinyl');

      // The surviving (OCR-backed) fact is still retrievable.
      const retrieved = await retriever.retrieve({
        userId,
        contactLabel: 'Alex',
        conversationText: hikeFact.content,
        topK: 10,
      });
      expect(retrieved.map((m) => m.content)).toContain(hikeFact.content);

      // Another user's keyboard data is untouched.
      expect(await memoriesOf(other.userId)).toEqual(otherBefore);
      expect(await storedMessages(otherKb)).toHaveLength(1);
    });

    it('every memory_opt_in:false purges (idempotent, incl. legacy false -> false); true never does', async () => {
      const { token, userId } = await signup();
      // A keyboard conversation of a user who is (still) opted out: what a
      // user who opted out under the old, non-erasing behaviour looks like.
      const kb = await createConversation(userId, HIKING, 'keyboard');

      const patch = (value: boolean) =>
        http()
          .patch('/me/settings')
          .set(auth(token))
          .send({ memory_opt_in: value });

      const legacy = await patch(false); // false -> false
      expect(legacy.status).toBe(200);
      expect(legacy.body).toEqual({
        settings: { memory_opt_in: false, training_opt_in: false },
        deleted: { conversations: 1, memories: 0 },
      });
      expect(await storedMessages(kb)).toHaveLength(0);

      const again = await patch(false); // nothing left: counts 0
      expect(again.body).toEqual({
        settings: { memory_opt_in: false, training_opt_in: false },
        deleted: { conversations: 0, memories: 0 },
      });

      const kb2 = await createConversation(userId, HIKING, 'keyboard');
      for (const res of [await patch(true), await patch(true)]) {
        expect(res.status).toBe(200);
        expect(res.body).toEqual({
          settings: { memory_opt_in: true, training_opt_in: false },
        });
      }
      expect(await storedMessages(kb2)).toHaveLength(1);
    });

    it(`purges more than PURGE_BATCH_SIZE (${PURGE_BATCH_SIZE}) conversations; a memory spanning two batches is erased, a shared one is re-pointed`, async () => {
      const { token, userId } = await signup();
      const n = PURGE_BATCH_SIZE + 5;
      const kbIds: string[] = [];
      for (let i = 0; i < n; i++) {
        kbIds.push(await createConversation(userId, `kb ${i}`, 'keyboard'));
      }
      kbIds.sort(); // the purge batches in id order
      const firstBatch = kbIds[0];
      const secondBatch = kbIds[n - 1];
      const ocr = await createConversation(userId, HIKING, 'ocr');

      const memory = async (
        content: string,
        sourceRef: string,
        links: string[],
      ): Promise<string> => {
        const [row] = await ds.query(
          `INSERT INTO memories (user_id, contact_label, kind, content, source, source_ref, status)
           VALUES ($1, 'Alex', 'interest', $2, 'extracted', $3, 'active')
           RETURNING id`,
          [userId, content, sourceRef],
        );
        for (const conv of links) {
          await ds.query(
            `INSERT INTO memory_sources (memory_id, conversation_id) VALUES ($1, $2)`,
            [row.id, conv],
          );
        }
        return row.id as string;
      };
      // Backed only by keyboard conversations in batch 1 AND batch 2.
      const crossBatch = await memory(
        'spans two batches',
        `conversation:${firstBatch}`,
        [firstBatch, secondBatch],
      );
      // Legacy row (no links) whose only source is in batch 2.
      const legacy = await memory(
        'legacy batch two',
        `conversation:${secondBatch}`,
        [],
      );
      // Keyboard (batch 1) + OCR: survives, re-pointed to the OCR source.
      const shared = await memory(
        'shared with ocr',
        `conversation:${firstBatch}`,
        [firstBatch, ocr],
      );

      await optIn(token, true);
      const res = await http()
        .patch('/me/settings')
        .set(auth(token))
        .send({ memory_opt_in: false });
      expect(res.status).toBe(200);
      expect(res.body.deleted).toEqual({ conversations: n, memories: 2 });

      const left = await memoriesOf(userId);
      expect(left.map((m) => m.id)).toEqual([shared]);
      expect(left[0].sources).toEqual([ocr]);
      expect(left[0].source_ref).toBe(`conversation:${ocr}`);
      expect(left.find((m) => m.id === crossBatch || m.id === legacy)).toBe(
        undefined,
      );
      const convs: Array<{ id: string }> = await ds.query(
        `SELECT id FROM conversations WHERE user_id = $1`,
        [userId],
      );
      expect(convs.map((c) => c.id)).toEqual([ocr]);
    });

    it('legacy backfill purges opted-out users that still hold keyboard data, and nobody else', async () => {
      const backfill = new LegacyOptOutPurgeService(
        ds,
        new ConversationErasureService(),
      );
      const legacyUser = await signup(); // opted out (default), has data
      const optedInUser = await signup(); // opted in: untouched
      const cleanUser = await signup(); // opted out, no keyboard data
      await optIn(optedInUser.token, true);

      const legacyKb = await createConversation(
        legacyUser.userId,
        HIKING,
        'keyboard',
      );
      const legacyOcr = await createConversation(
        legacyUser.userId,
        HIKING,
        'ocr',
      );
      const extraction = app.get(MemoryExtractionService);
      // Extract while opted in, then opt out the old way (flag only).
      await ds.query(`UPDATE users SET memory_opt_in = true WHERE id = $1`, [
        legacyUser.userId,
      ]);
      await extraction.extractForConversation(legacyKb);
      await extraction.extractForConversation(legacyOcr);
      await ds.query(`UPDATE users SET memory_opt_in = false WHERE id = $1`, [
        legacyUser.userId,
      ]);
      const keptKb = await createConversation(
        optedInUser.userId,
        HIKING,
        'keyboard',
      );
      const cleanOcr = await createConversation(
        cleanUser.userId,
        HIKING,
        'ocr',
      );

      const scope = [legacyUser, optedInUser, cleanUser].map((u) => u.userId);
      const summary = await backfill.run({ userIds: scope });
      expect(summary).toEqual({
        users: 1,
        conversations: 1,
        memories: 0, // the hiking fact is also backed by the OCR conversation
        failed: 0,
      });
      expect(await storedMessages(legacyKb)).toHaveLength(0);
      expect(await storedMessages(legacyOcr)).toHaveLength(1);
      const [fact] = await memoriesOf(legacyUser.userId);
      expect(fact.sources).toEqual([legacyOcr]);
      expect(fact.source_ref).toBe(`conversation:${legacyOcr}`);
      expect(await storedMessages(keptKb)).toHaveLength(1);
      expect(await storedMessages(cleanOcr)).toHaveLength(1);

      // Idempotent: a second run finds nobody.
      expect(await backfill.run({ userIds: scope })).toEqual({
        users: 0,
        conversations: 0,
        memories: 0,
        failed: 0,
      });
    });
  });
});
