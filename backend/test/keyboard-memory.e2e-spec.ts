import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { DataSource } from 'typeorm';
import { AppModule } from '../src/app.module';
import { AllExceptionsFilter } from '../src/common/filters/all-exceptions.filter';
import { MemoryExtractionService } from '../src/memory/memory-extraction.service';
import { MemoryWriterService } from '../src/memory/memory-writer.service';

/**
 * Android Phase 0 against a real, migrated Postgres:
 *  - `memory_opt_in` defaults to false and is toggled via PATCH /me/settings;
 *  - keyboard captures are persisted only when opted in AND platform +
 *    contact_label are present AND remember != false;
 *  - overlapping captures merge without duplicates (source = 'keyboard');
 *  - DELETE /conversations/:id is owner-scoped (404 for others, no IDOR) and
 *    erases messages + memories sourced from the conversation.
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
    expect(me.body.settings).toEqual({ memory_opt_in: false });

    await optIn(token, true);
    const after = await http().get('/me').set(auth(token));
    expect(after.body.settings.memory_opt_in).toBe(true);
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

      await optIn(token, false);
      const res = await extraction.extractForConversation(conv);
      expect(res.status).toBe('opted-out');
      expect(await memoriesOf(userId)).toHaveLength(0);
    });
  });
});
