import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { DataSource } from 'typeorm';
import { AppModule } from '../src/app.module';
import { AllExceptionsFilter } from '../src/common/filters/all-exceptions.filter';

/**
 * End-to-end QA of the Share-Menu backend contract (P3 / DAI-122) against a real
 * DB. Verifies the one-round-trip analyze, that it requires auth, is metered
 * against the shared screenshot quota, and rejects blank OCR without spending
 * quota — composing the existing WS-5 ingest + analyze, tagged source=share.
 */
describe('Share contract P3 (e2e)', () => {
  let app: INestApplication;
  let ds: DataSource;

  const http = () => request(app.getHttpServer());
  const auth = (t: string) => ({ Authorization: `Bearer ${t}` });

  const signup = async (): Promise<string> => {
    const email = `share-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`;
    const res = await http()
      .post('/auth/signup')
      .send({ email, password: 'password123' });
    expect(res.status).toBe(201);
    return res.body.access_token as string;
  };

  const body = () => ({
    ocr_text: 'them: hey, fun weekend?\nme: pretty good! you?',
    extracted_messages: [
      { sender: 'them', content: 'hey, fun weekend?' },
      { sender: 'me', content: 'pretty good! you?' },
    ],
    platform: 'instagram',
    contact_label: 'Alex',
  });

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

  it('rejects unauthenticated share calls with 401', async () => {
    const res = await http().post('/v1/share/analyze').send(body());
    expect(res.status).toBe(401);
  });

  it('persists a shared conversation and returns analysis + usage (source=share)', async () => {
    const token = await signup();
    const res = await http()
      .post('/v1/share/analyze')
      .set(auth(token))
      .send(body());

    expect(res.status).toBe(200);
    expect(res.body.screenshot_id).toEqual(expect.any(String));
    expect(res.body.conversation_id).toEqual(expect.any(String));
    expect(typeof res.body.summary).toBe('string');
    expect(Number.isInteger(res.body.interest_score)).toBe(true);
    expect(res.body.interest_score).toBeGreaterThanOrEqual(0);
    expect(res.body.interest_score).toBeLessThanOrEqual(100);
    expect(Array.isArray(res.body.suggested_replies)).toBe(true);
    // Metered against the shared screenshot counter; analysis isn't re-metered.
    expect(res.body.usage.screenshots_used).toBe(1);
    expect(res.body.usage.screenshots_limit).toBe(5);

    // Persisted with no raw image (retention policy).
    const shot = await ds.query(
      `SELECT s3_key, ocr_status FROM screenshots WHERE id = $1`,
      [res.body.screenshot_id],
    );
    expect(shot[0].s3_key).toBeNull();
    expect(shot[0].ocr_status).toBe('succeeded');
  });

  it('blank OCR → 422 OCR_FAILED and the screenshot counter is unchanged', async () => {
    const token = await signup();
    const res = await http()
      .post('/v1/share/analyze')
      .set(auth(token))
      .send({ ...body(), ocr_text: '   ' });
    expect(res.status).toBe(422);
    expect(res.body.error.code).toBe('OCR_FAILED');

    const me = await http().get('/me').set(auth(token));
    expect(me.body.usage.screenshots_used).toBe(0);
  });

  it('counts each share against the daily screenshot quota', async () => {
    const token = await signup();
    await http().post('/v1/share/analyze').set(auth(token)).send(body());
    const second = await http()
      .post('/v1/share/analyze')
      .set(auth(token))
      .send(body());
    expect(second.body.usage.screenshots_used).toBe(2);
  });
});
