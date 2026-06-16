import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { DataSource } from 'typeorm';
import { AppModule } from '../src/app.module';
import { AllExceptionsFilter } from '../src/common/filters/all-exceptions.filter';

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
