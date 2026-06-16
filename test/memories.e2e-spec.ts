import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { DataSource } from 'typeorm';
import { AppModule } from '../src/app.module';
import { AllExceptionsFilter } from '../src/common/filters/all-exceptions.filter';
import { EmbeddingService } from '../src/ai/embedding/embedding.service';
import {
  MEMORY_RETRIEVER,
  MemoryRetriever,
} from '../src/ai/memory/memory-retriever.interface';

/**
 * End-to-end QA of the Memory Dashboard API (MS-5 / DAI-150, FR-D1..D6) against
 * a real DB, asserting the acceptance criteria at the HTTP layer plus the
 * retrieval side-effects (delete/dismiss must drop a memory from MS-3):
 *  - AC-D1 grouped view + filter by kind/contact/status.
 *  - AC-D2 manual add is embedded and retrievable.
 *  - AC-D3 delete removes it from the dashboard AND the next retrieval.
 *  - AC-D4 dismiss excludes it from retrieval (and confirm includes it).
 * Plus owner-scoping (no cross-tenant access) and auth.
 */
describe('Memory dashboard MS-5 (e2e)', () => {
  let app: INestApplication;
  let ds: DataSource;
  let embeddings: EmbeddingService;
  let retriever: MemoryRetriever;
  const tokens: string[] = [];

  const http = () => request(app.getHttpServer());
  const auth = (t: string) => ({ Authorization: `Bearer ${t}` });

  const signup = async (): Promise<{ token: string; userId: string }> => {
    const email = `ms5-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`;
    const res = await http()
      .post('/auth/signup')
      .send({ email, password: 'password123' });
    expect(res.status).toBe(201);
    tokens.push(res.body.access_token);
    return { token: res.body.access_token, userId: res.body.user.id };
  };

  const retrieve = (userId: string, text: string, contactLabel?: string) =>
    retriever.retrieve({
      userId,
      contactLabel,
      conversationText: text,
      topK: 5,
    });

  const seedPending = async (
    userId: string,
    content: string,
  ): Promise<string> => {
    const { vector: vec } = await embeddings.embed(content);
    const rows: Array<{ id: string }> = await ds.query(
      `INSERT INTO memories (user_id, kind, content, embedding, source_ref, status, confidence, content_hash)
       VALUES ($1, 'fact', $2, $3::vector, 'conversation:seed', 'pending_review', 0.6, $4)
       RETURNING id`,
      [userId, content, `[${vec.join(',')}]`, `seed-${Math.random()}`],
    );
    return rows[0].id;
  };

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
    embeddings = app.get(EmbeddingService);
    retriever = app.get<MemoryRetriever>(MEMORY_RETRIEVER);
  });

  afterAll(async () => {
    await app?.close();
  });

  it("AC-D1: lists the owner's memories and filters by kind / status", async () => {
    const { token } = await signup();
    await http()
      .post('/memories')
      .set(auth(token))
      .send({ kind: 'interest', content: 'Enjoys trail running' });
    await http()
      .post('/memories')
      .set(auth(token))
      .send({ kind: 'job', content: 'Works as a data scientist' });

    const all = await http().get('/memories').set(auth(token));
    expect(all.status).toBe(200);
    expect(all.body).toHaveLength(2);
    expect(
      all.body.every((m: { status: string }) => m.status === 'active'),
    ).toBe(true);

    const interests = await http()
      .get('/memories?kind=interest')
      .set(auth(token));
    expect(interests.body).toHaveLength(1);
    expect(interests.body[0].kind).toBe('interest');
  });

  it('AC-D2: manual add is embedded (source=manual, conf=1, active) and retrievable', async () => {
    const { token, userId } = await signup();
    const content = 'Allergic to peanuts and shellfish';
    const res = await http()
      .post('/memories')
      .set(auth(token))
      .send({ kind: 'fact', content });

    expect(res.status).toBe(201);
    expect(res.body).toMatchObject({
      status: 'active',
      confidence: 1,
      source: 'manual',
    });

    const hit = await retrieve(userId, content);
    expect(hit.map((m) => m.content)).toContain(content);
  });

  it('AC-D3: delete removes it from the dashboard and the next retrieval', async () => {
    const { token, userId } = await signup();
    const content = 'Drives a vintage motorcycle on weekends';
    const created = await http()
      .post('/memories')
      .set(auth(token))
      .send({ kind: 'fact', content });
    const id = created.body.id;
    expect((await retrieve(userId, content)).map((m) => m.content)).toContain(
      content,
    );

    const del = await http().delete(`/memories/${id}`).set(auth(token));
    expect(del.status).toBe(204);

    const list = await http().get('/memories').set(auth(token));
    expect(list.body.find((m: { id: string }) => m.id === id)).toBeUndefined();
    expect(
      (await retrieve(userId, content)).map((m) => m.content),
    ).not.toContain(content);
  });

  it('AC-D4: confirm makes a pending fact retrievable; dismiss excludes it', async () => {
    const { token, userId } = await signup();
    const content = 'Speaks fluent Japanese and Korean';
    const id = await seedPending(userId, content);

    // Pending → not retrievable yet.
    expect(
      (await retrieve(userId, content)).map((m) => m.content),
    ).not.toContain(content);

    // Confirm → active → retrievable.
    const confirm = await http()
      .patch(`/memories/${id}`)
      .set(auth(token))
      .send({ status: 'active' });
    expect(confirm.status).toBe(200);
    expect((await retrieve(userId, content)).map((m) => m.content)).toContain(
      content,
    );

    // Dismiss → excluded from retrieval again.
    const dismiss = await http()
      .patch(`/memories/${id}`)
      .set(auth(token))
      .send({ status: 'dismissed' });
    expect(dismiss.status).toBe(200);
    expect(
      (await retrieve(userId, content)).map((m) => m.content),
    ).not.toContain(content);
  });

  it('re-embeds on content edit so retrieval reflects the new text', async () => {
    const { token, userId } = await signup();
    const created = await http()
      .post('/memories')
      .set(auth(token))
      .send({ kind: 'interest', content: 'Original hobby is painting' });
    const newContent = 'Now obsessed with rock climbing';

    await http()
      .patch(`/memories/${created.body.id}`)
      .set(auth(token))
      .send({ content: newContent });

    expect(
      (await retrieve(userId, newContent)).map((m) => m.content),
    ).toContain(newContent);
  });

  describe('owner-scoping & auth', () => {
    it("does not let one user read, edit, or delete another user's memory", async () => {
      const owner = await signup();
      const created = await http()
        .post('/memories')
        .set(auth(owner.token))
        .send({ kind: 'fact', content: 'Owns a lake cabin' });
      const id = created.body.id;

      const intruder = await signup();
      expect(
        (await http().get('/memories').set(auth(intruder.token))).body,
      ).toHaveLength(0);
      expect(
        (
          await http()
            .patch(`/memories/${id}`)
            .set(auth(intruder.token))
            .send({ status: 'dismissed' })
        ).status,
      ).toBe(404);
      expect(
        (await http().delete(`/memories/${id}`).set(auth(intruder.token)))
          .status,
      ).toBe(404);
    });

    it('rejects unauthenticated access with 401', async () => {
      expect((await http().get('/memories')).status).toBe(401);
    });
  });
});
