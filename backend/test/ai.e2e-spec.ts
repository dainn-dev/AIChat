import { INestApplication, ValidationPipe } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { AiModule } from '../src/ai/ai.module';
import { ObservabilityModule } from '../src/observability/observability.module';
import { AllExceptionsFilter } from '../src/common/filters/all-exceptions.filter';
import configuration from '../src/config/configuration';

/**
 * Exercises the WS-4 AI endpoints end-to-end against the deterministic stub
 * provider (no DB, no auth, no external LLM). Validates DAI-124 §4 acceptance
 * criteria AC-R1, AC-R3, AC-N1, AC-N2 at the HTTP layer.
 */
describe('AI endpoints (e2e)', () => {
  let app: INestApplication;

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [
        ConfigModule.forRoot({ isGlobal: true, load: [configuration] }),
        ObservabilityModule,
        AiModule,
      ],
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

  const conversation = [
    { sender: 'them', content: 'hey, fun weekend?' },
    { sender: 'me', content: 'pretty good! you?' },
  ];

  it('POST /ai/reply returns >=1 reply tagged with the requested tone (AC-R1)', async () => {
    const res = await request(app.getHttpServer())
      .post('/ai/reply')
      .send({ messages: conversation, tone: 'Flirty', count: 2 });

    expect(res.status).toBe(200);
    expect(Array.isArray(res.body.replies)).toBe(true);
    expect(res.body.replies.length).toBeGreaterThanOrEqual(1);
    expect(res.body.replies[0].tone).toBe('Flirty');
    expect(res.body).toHaveProperty('usage');
  });

  it('POST /ai/rewrite returns a rewritten draft (AC-R3)', async () => {
    const res = await request(app.getHttpServer())
      .post('/ai/rewrite')
      .send({ text: 'ok', tone: 'Funny' });

    expect(res.status).toBe(200);
    expect(typeof res.body.text).toBe('string');
    expect(res.body.text.length).toBeGreaterThan(0);
  });

  it('POST /ai/analyze returns structured analysis with a clamped score (AC-N1)', async () => {
    const res = await request(app.getHttpServer())
      .post('/ai/analyze')
      .send({ messages: conversation });

    expect(res.status).toBe(200);
    expect(typeof res.body.summary).toBe('string');
    expect(res.body.summary.length).toBeGreaterThan(0);
    expect(Number.isInteger(res.body.interest_score)).toBe(true);
    expect(res.body.interest_score).toBeGreaterThanOrEqual(0);
    expect(res.body.interest_score).toBeLessThanOrEqual(100);
    expect(res.body.suggested_replies.length).toBeGreaterThanOrEqual(1);
    expect(Array.isArray(res.body.red_flags)).toBe(true);
  });

  it('rejects an invalid tone with the common error envelope', async () => {
    const res = await request(app.getHttpServer())
      .post('/ai/reply')
      .send({ messages: conversation, tone: 'Sarcastic' });

    expect(res.status).toBe(400);
    expect(res.body.error).toBeDefined();
    expect(res.body.error.code).toBeDefined();
  });

  it('rejects an empty conversation', async () => {
    const res = await request(app.getHttpServer())
      .post('/ai/analyze')
      .send({ messages: [] });

    expect(res.status).toBe(400);
    expect(res.body.error).toBeDefined();
  });
});
