import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { AppModule } from '../src/app.module';
import { AllExceptionsFilter } from '../src/common/filters/all-exceptions.filter';

/**
 * End-to-end QA of the keyboard backend contract (P4-1 / DAI-136). Verifies the
 * three endpoints work over the pipeline, require auth, and are metered against
 * the shared per-user daily counter (each call increments `replies_used`).
 */
describe('Keyboard contract P4-1 (e2e)', () => {
  let app: INestApplication;

  const http = () => request(app.getHttpServer());
  const auth = (t: string) => ({ Authorization: `Bearer ${t}` });

  const signup = async (): Promise<string> => {
    const email = `kbd-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`;
    const res = await http()
      .post('/auth/signup')
      .send({ email, password: 'password123' });
    expect(res.status).toBe(201);
    return res.body.access_token as string;
  };

  const conversation = [
    { sender: 'them', content: 'hey, fun weekend?' },
    { sender: 'me', content: 'pretty good! you?' },
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
  });

  afterAll(async () => {
    await app?.close();
  });

  it('rejects unauthenticated keyboard calls with 401', async () => {
    const res = await http()
      .post('/v1/keyboard/reply')
      .send({ conversation, tone: 'Friendly' });
    expect(res.status).toBe(401);
  });

  it('POST /v1/keyboard/reply returns tone-tagged suggestions + usage', async () => {
    const token = await signup();
    const res = await http()
      .post('/v1/keyboard/reply')
      .set(auth(token))
      .send({ platform: 'whatsapp', conversation, tone: 'Flirty', count: 2 });

    expect(res.status).toBe(200);
    expect(Array.isArray(res.body.suggestions)).toBe(true);
    expect(res.body.suggestions.length).toBeGreaterThanOrEqual(1);
    expect(res.body.suggestions[0].tone).toBe('Flirty');
    // Metered against the shared per-user reply counter.
    expect(res.body.usage.replies_used).toBe(1);
    expect(res.body.usage.replies_limit).toBe(20);
  });

  it('POST /v1/keyboard/rewrite returns rewritten text + usage', async () => {
    const token = await signup();
    const res = await http()
      .post('/v1/keyboard/rewrite')
      .set(auth(token))
      .send({ text: 'ok sounds good', tone: 'Professional' });

    expect(res.status).toBe(200);
    expect(typeof res.body.text).toBe('string');
    expect(res.body.text.length).toBeGreaterThan(0);
    expect(res.body.usage.replies_used).toBe(1);
  });

  it('POST /v1/keyboard/translate returns translated text + usage', async () => {
    const token = await signup();
    const res = await http()
      .post('/v1/keyboard/translate')
      .set(auth(token))
      .send({ text: 'hen gap lai nhe', target_lang: 'en', source_lang: 'vi' });

    expect(res.status).toBe(200);
    expect(typeof res.body.text).toBe('string');
    expect(res.body.text.length).toBeGreaterThan(0);
    expect(res.body.usage.replies_used).toBe(1);
  });

  it('shares one per-user counter across reply/rewrite/translate', async () => {
    const token = await signup();
    await http()
      .post('/v1/keyboard/reply')
      .set(auth(token))
      .send({ conversation, tone: 'Friendly' });
    await http()
      .post('/v1/keyboard/rewrite')
      .set(auth(token))
      .send({ text: 'hi', tone: 'Funny' });
    const third = await http()
      .post('/v1/keyboard/translate')
      .set(auth(token))
      .send({ text: 'hi', target_lang: 'es' });

    // reply + rewrite + translate all hit the same daily counter.
    expect(third.body.usage.replies_used).toBe(3);
  });

  it('validates payloads (bad tone → 400)', async () => {
    const token = await signup();
    const res = await http()
      .post('/v1/keyboard/reply')
      .set(auth(token))
      .send({ conversation, tone: 'Sarcastic' });
    expect(res.status).toBe(400);
  });
});
