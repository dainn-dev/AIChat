import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { DataSource } from 'typeorm';
import { AppModule } from '../src/app.module';
import { AllExceptionsFilter } from '../src/common/filters/all-exceptions.filter';
import { ScreenshotsService } from '../src/screenshots/screenshots.service';

/**
 * End-to-end QA of WS-5 screenshots (DAI-129 / DAI-143) against a real
 * PostgreSQL with migrations applied. Exercises the acceptance criteria from
 * the issue at the HTTP layer plus direct DB assertions:
 *
 *  - AC-O1: valid {ocr_text, extracted_messages[]} -> conversation + messages
 *    (source='ocr') + screenshots row persisted; s3_key NULL (no raw image).
 *  - AC-O2: blank/garbled input -> 422 OCR_FAILED, screenshot counter unchanged.
 *  - AC-O3: Free tier -> 5 screenshots/day succeed, 6th rejected; counter shows
 *    exactly 5, no over-count under concurrent calls.
 *  - Retention cap: a conversation keeps only its newest 2,000 messages
 *    (oldest dropped on append), same as keyboard captures.
 *  - POST /screenshots/:id/analyze: analysis for an owned id; 404 for
 *    missing/foreign id; not separately metered.
 */
describe('Screenshots WS-5 (e2e)', () => {
  let app: INestApplication;
  let ds: DataSource;

  const validBody = () => ({
    ocr_text: 'them: hey, fun weekend?\nme: pretty good! you?',
    extracted_messages: [
      { sender: 'them', content: 'hey, fun weekend?' },
      { sender: 'me', content: 'pretty good! you?' },
    ],
    platform: 'whatsapp',
    contact_label: 'Alex',
  });

  /** Sign up a fresh free-tier user and return its bearer token. */
  const signup = async (): Promise<string> => {
    const email = `ws5-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`;
    const res = await http()
      .post('/auth/signup')
      .send({ email, password: 'password123', display_name: 'WS5 QA' });
    expect(res.status).toBe(201);
    expect(res.body.user.tier).toBe('free');
    return res.body.access_token as string;
  };

  const auth = (token: string) => ({ Authorization: `Bearer ${token}` });

  const screenshotsUsed = async (token: string): Promise<number> => {
    const me = await http().get('/me').set(auth(token));
    expect(me.status).toBe(200);
    return me.body.usage.screenshots_used as number;
  };

  function http() {
    return request(app.getHttpServer());
  }

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

  it('AC-O1: valid OCR ingest persists conversation + ocr messages + screenshot (s3_key NULL); counter = 1', async () => {
    const token = await signup();

    const res = await http()
      .post('/screenshots')
      .set(auth(token))
      .send(validBody());

    expect(res.status).toBe(201);
    const { screenshot_id, conversation_id } = res.body;
    expect(screenshot_id).toEqual(expect.any(String));
    expect(conversation_id).toEqual(expect.any(String));
    expect(res.body.extracted_messages).toHaveLength(2);
    expect(res.body.usage.screenshots_used).toBe(1);
    expect(res.body.usage.screenshots_limit).toBe(5);

    // Screenshot row: no raw image is stored (retention policy), OCR succeeded.
    const shot = await ds.query(
      `SELECT s3_key, ocr_status, ocr_text, conversation_id
         FROM screenshots WHERE id = $1`,
      [screenshot_id],
    );
    expect(shot).toHaveLength(1);
    expect(shot[0].s3_key).toBeNull();
    expect(shot[0].ocr_status).toBe('succeeded');
    expect(shot[0].conversation_id).toBe(conversation_id);

    // Conversation persisted with the supplied platform/contact label.
    const conv = await ds.query(
      `SELECT platform, contact_label FROM conversations WHERE id = $1`,
      [conversation_id],
    );
    expect(conv).toHaveLength(1);
    expect(conv[0].platform).toBe('whatsapp');

    // Messages persisted with source='ocr', preserving order and attribution.
    const msgs = await ds.query(
      `SELECT sender, content, source, position
         FROM messages WHERE conversation_id = $1 ORDER BY position ASC`,
      [conversation_id],
    );
    expect(msgs).toHaveLength(2);
    expect(msgs.every((m: { source: string }) => m.source === 'ocr')).toBe(
      true,
    );
    expect(msgs[0]).toMatchObject({ sender: 'them', position: 0 });
    expect(msgs[1]).toMatchObject({ sender: 'me', position: 1 });

    // Counter advanced exactly once.
    expect(await screenshotsUsed(token)).toBe(1);
  });

  it('AC-O2: blank OCR text -> 422 OCR_FAILED and the screenshot counter never moves', async () => {
    const token = await signup();

    const res = await http()
      .post('/screenshots')
      .set(auth(token))
      .send({ ...validBody(), ocr_text: '   ' });

    expect(res.status).toBe(422);
    expect(res.body.error.code).toBe('OCR_FAILED');

    // Garbled => only whitespace messages also fails the same way.
    const garbled = await http()
      .post('/screenshots')
      .set(auth(token))
      .send({
        ocr_text: '\n\n',
        extracted_messages: [{ sender: 'them', content: '   ' }],
      });
    expect(garbled.status).toBe(422);
    expect(garbled.body.error.code).toBe('OCR_FAILED');

    // Counter unchanged and nothing persisted for this user.
    expect(await screenshotsUsed(token)).toBe(0);
  });

  it('AC-O3 (sequential): 5 screenshots/day succeed, the 6th is rejected 429; counter shows exactly 5', async () => {
    const token = await signup();

    for (let i = 1; i <= 5; i++) {
      const res = await http()
        .post('/screenshots')
        .set(auth(token))
        .send(validBody());
      expect(res.status).toBe(201);
      expect(res.body.usage.screenshots_used).toBe(i);
    }

    const sixth = await http()
      .post('/screenshots')
      .set(auth(token))
      .send(validBody());
    expect(sixth.status).toBe(429);
    expect(sixth.body.error.code).toBe('QUOTA_EXCEEDED');
    expect(sixth.body.error.details).toMatchObject({
      metric: 'screenshot',
      limit: 5,
    });

    expect(await screenshotsUsed(token)).toBe(5);
  });

  it('AC-O3 (concurrent): 8 simultaneous uploads never over-count; exactly 5 succeed and counter = 5', async () => {
    const token = await signup();

    const results = await Promise.all(
      Array.from({ length: 8 }, () =>
        http().post('/screenshots').set(auth(token)).send(validBody()),
      ),
    );
    const statuses = results.map((r) => r.status);
    const ok = statuses.filter((s) => s === 201).length;
    const limited = statuses.filter((s) => s === 429).length;

    expect(ok).toBe(5);
    expect(limited).toBe(3);
    // The DB counter must equal the limit — proof the reservation is atomic.
    expect(await screenshotsUsed(token)).toBe(5);
  });

  it('retention cap (security M4): appending past 2,000 messages via ingest keeps only the newest 2,000, in order; memory_sources links survive', async () => {
    const token = await signup();
    const first = await http()
      .post('/screenshots')
      .set(auth(token))
      .send(validBody());
    expect(first.status).toBe(201);
    const conv = first.body.conversation_id as string;
    const userId: string = (
      await ds.query(`SELECT user_id FROM conversations WHERE id = $1`, [conv])
    )[0].user_id;

    // Fill positions 2..1989 directly (ingest accepts at most 200 per call
    // and Free allows 5/day), so the conversation holds 1,990 messages.
    await ds.query(
      `INSERT INTO messages (conversation_id, sender, content, position, source)
       SELECT $1, (CASE WHEN n % 2 = 0 THEN 'them' ELSE 'me' END)::message_sender,
              'seed-' || n, n, 'ocr'
         FROM generate_series(2, 1989) AS n`,
      [conv],
    );
    // A memory extracted from this conversation (link is per conversation).
    const [mem] = await ds.query(
      `INSERT INTO memories (user_id, contact_label, kind, content, source, source_ref, status)
       VALUES ($1, 'Alex', 'interest', 'Likes weekends', 'extracted', $2, 'active')
       RETURNING id`,
      [userId, `conversation:${conv}`],
    );
    await ds.query(
      `INSERT INTO memory_sources (memory_id, conversation_id) VALUES ($1, $2)`,
      [mem.id, conv],
    );

    // Append 20 more → positions 1990..2009; the oldest 10 (0..9) go.
    const appended = Array.from({ length: 20 }, (_, i) => ({
      sender: i % 2 === 0 ? 'them' : 'me',
      content: `new-${i}`,
    }));
    const res = await http()
      .post('/screenshots')
      .set(auth(token))
      .send({
        ocr_text: appended.map((m) => `${m.sender}: ${m.content}`).join('\n'),
        extracted_messages: appended,
        conversation_id: conv,
      });
    expect(res.status).toBe(201);
    expect(res.body.conversation_id).toBe(conv);

    const rows: Array<{ position: number; content: string }> = await ds.query(
      `SELECT position, content FROM messages
        WHERE conversation_id = $1 ORDER BY position ASC`,
      [conv],
    );
    expect(rows).toHaveLength(2000);
    expect(rows.map((r) => Number(r.position))).toEqual(
      Array.from({ length: 2000 }, (_, i) => i + 10),
    );
    expect(rows[0].content).toBe('seed-10');
    expect(rows.slice(-20).map((r) => r.content)).toEqual(
      appended.map((m) => m.content),
    );

    const links = await ds.query(
      `SELECT 1 FROM memory_sources WHERE memory_id = $1 AND conversation_id = $2`,
      [mem.id, conv],
    );
    expect(links).toHaveLength(1);
    const kept = await ds.query(`SELECT 1 FROM memories WHERE id = $1`, [
      mem.id,
    ]);
    expect(kept).toHaveLength(1);
  });

  it('retention cap: concurrent appends to one conversation keep distinct, contiguous positions', async () => {
    const token = await signup();
    const first = await http()
      .post('/screenshots')
      .set(auth(token))
      .send(validBody());
    const conv = first.body.conversation_id as string;
    const results = await Promise.all(
      [0, 1, 2].map((k) =>
        http()
          .post('/screenshots')
          .set(auth(token))
          .send({
            ocr_text: `me: c${k}`,
            extracted_messages: [
              { sender: 'me', content: `c${k}-a` },
              { sender: 'them', content: `c${k}-b` },
            ],
            conversation_id: conv,
          }),
      ),
    );
    results.forEach((r) => expect(r.status).toBe(201));
    const rows: Array<{ position: number }> = await ds.query(
      `SELECT position FROM messages WHERE conversation_id = $1 ORDER BY position`,
      [conv],
    );
    expect(rows.map((r) => Number(r.position))).toEqual([
      0, 1, 2, 3, 4, 5, 6, 7,
    ]);
  });

  it('rejects appending to a keyboard conversation (400 CONVERSATION_NOT_APPENDABLE); counter unchanged', async () => {
    const token = await signup();
    const me = await http().get('/me').set(auth(token));
    const [kb] = await ds.query(
      `INSERT INTO conversations (user_id, platform, contact_label, source)
       VALUES ($1, 'zalo', 'Alex', 'keyboard') RETURNING id`,
      [me.body.user.id],
    );
    const res = await http()
      .post('/screenshots')
      .set(auth(token))
      .send({ ...validBody(), conversation_id: kb.id });
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('CONVERSATION_NOT_APPENDABLE');
    expect(await screenshotsUsed(token)).toBe(0);
    const msgs = await ds.query(
      `SELECT 1 FROM messages WHERE conversation_id = $1`,
      [kb.id],
    );
    expect(msgs).toHaveLength(0);
  });

  it('a conversation gone by the in-transaction re-check is a 404 and the reserved quota is released', async () => {
    const token = await signup();
    // Simulate the race: the pre-quota ownership check passed, then the
    // conversation was erased before the append transaction locked it.
    const precheck = jest
      .spyOn(
        ScreenshotsService.prototype as unknown as {
          assertConversationOwned: () => Promise<void>;
        },
        'assertConversationOwned',
      )
      .mockResolvedValueOnce(undefined);
    try {
      const res = await http()
        .post('/screenshots')
        .set(auth(token))
        .send({
          ...validBody(),
          conversation_id: '00000000-0000-4000-8000-000000000000',
        });
      expect(res.status).toBe(404);
      expect(res.body.error.code).toBe('CONVERSATION_NOT_FOUND');
      expect(precheck).toHaveBeenCalledTimes(1);
    } finally {
      precheck.mockRestore();
    }
    expect(await screenshotsUsed(token)).toBe(0);
  });

  describe('POST /screenshots/:id/analyze', () => {
    it('returns analysis for an owned screenshot and does NOT consume screenshot quota', async () => {
      const token = await signup();
      const created = await http()
        .post('/screenshots')
        .set(auth(token))
        .send(validBody());
      expect(created.status).toBe(201);
      expect(await screenshotsUsed(token)).toBe(1);

      const res = await http()
        .post(`/screenshots/${created.body.screenshot_id}/analyze`)
        .set(auth(token));

      expect(res.status).toBe(200);
      expect(typeof res.body.summary).toBe('string');
      expect(Number.isInteger(res.body.interest_score)).toBe(true);
      expect(res.body.interest_score).toBeGreaterThanOrEqual(0);
      expect(res.body.interest_score).toBeLessThanOrEqual(100);
      expect(Array.isArray(res.body.suggested_replies)).toBe(true);
      expect(res.body).toHaveProperty('usage');

      // Not separately metered: still 1 after analyze.
      expect(await screenshotsUsed(token)).toBe(1);
    });

    it('404s for a missing screenshot id', async () => {
      const token = await signup();
      const res = await http()
        .post('/screenshots/00000000-0000-0000-0000-000000000000/analyze')
        .set(auth(token));
      expect(res.status).toBe(404);
      expect(res.body.error.code).toBe('SCREENSHOT_NOT_FOUND');
    });

    it("404s for another user's screenshot (no cross-tenant access)", async () => {
      const owner = await signup();
      const created = await http()
        .post('/screenshots')
        .set(auth(owner))
        .send(validBody());
      expect(created.status).toBe(201);

      const intruder = await signup();
      const res = await http()
        .post(`/screenshots/${created.body.screenshot_id}/analyze`)
        .set(auth(intruder));
      expect(res.status).toBe(404);
      expect(res.body.error.code).toBe('SCREENSHOT_NOT_FOUND');
    });

    it('rejects unauthenticated requests with 401 (AC-A5)', async () => {
      const res = await http().post('/screenshots').send(validBody());
      expect(res.status).toBe(401);
    });
  });
});
