import { INestApplication } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import { DataSource } from 'typeorm';
import { AppModule } from '../src/app.module';
import { EmbeddingService } from '../src/ai/embedding/embedding.service';
import {
  MEMORY_RETRIEVER,
  MemoryRetriever,
} from '../src/ai/memory/memory-retriever.interface';

/**
 * End-to-end QA of the Phase-2 RAG retrieval (MS-3 / DAI-148) against a real
 * pgvector DB with the HNSW index (MS-2) and the embedding service (MS-1).
 * Verifies the acceptance criteria with the deterministic stub embedder:
 *
 *  - AC-RT1 relevance: a memory matching the query context is retrieved.
 *  - AC-RT2 zero-regression: a user with no memories yields [].
 *  - AC-RT3 threshold: an unrelated memory is excluded (below cosine threshold).
 *  - AC-RT5 cross-user isolation: one user never sees another user's memories.
 *  - Contact scoping: contact-specific memories are gated by the active contact;
 *    global facts (contact_label NULL) are always eligible.
 *
 * Phrases are chosen so identical query/memory text → cosine ≈ 1 (clears the
 * 0.75 default), and token-disjoint text → cosine ≈ 0 (excluded) — exercising
 * the real SQL + index path, not the embedding quality.
 */
describe('Memory retrieval MS-3 (e2e)', () => {
  let app: INestApplication;
  let ds: DataSource;
  let embeddings: EmbeddingService;
  let retriever: MemoryRetriever;
  const userIds: string[] = [];

  const PHRASE_GLOBAL =
    'global preference the user is vegetarian and dislikes very spicy food';
  const PHRASE_CONTACT =
    'contact alex enjoys hiking climbing and outdoor camping weekend trips';
  const PHRASE_NOISE =
    'unrelated quantum chromodynamics lattice gauge theory homework problems';

  const createUser = async (tag: string): Promise<string> => {
    const rows: Array<{ id: string }> = await ds.query(
      `INSERT INTO users (email, password_hash) VALUES ($1, $2) RETURNING id`,
      [`ms3-rt-${tag}-${Date.now()}-${Math.random()}@example.com`, 'x'],
    );
    userIds.push(rows[0].id);
    return rows[0].id;
  };

  const seedMemory = async (
    userId: string,
    kind: string,
    content: string,
    contactLabel: string | null = null,
  ): Promise<void> => {
    const { vector } = await embeddings.embed(content);
    await ds.query(
      `INSERT INTO memories (user_id, contact_label, kind, content, embedding)
       VALUES ($1, $2, $3, $4, $5::vector)`,
      [userId, contactLabel, kind, content, `[${vector.join(',')}]`],
    );
  };

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = moduleRef.createNestApplication();
    await app.init();
    ds = app.get(DataSource);
    embeddings = app.get(EmbeddingService);
    retriever = app.get<MemoryRetriever>(MEMORY_RETRIEVER);
  });

  afterAll(async () => {
    if (userIds.length) {
      await ds.query(`DELETE FROM users WHERE id = ANY($1::uuid[])`, [userIds]);
    }
    await app?.close();
  });

  it('binds the real pgvector retriever in Phase 2 (not the stub)', () => {
    expect(retriever.constructor.name).toBe('PgVectorMemoryRetriever');
  });

  it('AC-RT2: a user with no memories retrieves []', async () => {
    const userId = await createUser('empty');
    const res = await retriever.retrieve({
      userId,
      conversationText: PHRASE_GLOBAL,
      topK: 5,
    });
    expect(res).toEqual([]);
  });

  it('AC-RT1 + AC-RT3: returns the relevant memory and excludes unrelated noise', async () => {
    const userId = await createUser('relevance');
    await seedMemory(userId, 'preference', PHRASE_GLOBAL);
    await seedMemory(userId, 'misc', PHRASE_NOISE);

    const res = await retriever.retrieve({
      userId,
      conversationText: PHRASE_GLOBAL,
      topK: 5,
    });

    const contents = res.map((m) => m.content);
    expect(contents).toContain(PHRASE_GLOBAL); // AC-RT1: relevant retrieved
    expect(contents).not.toContain(PHRASE_NOISE); // AC-RT3: noise excluded
    expect(res[0].kind).toBe('preference');
  });

  it('AC-RT3: a query unrelated to all stored memories retrieves []', async () => {
    const userId = await createUser('threshold');
    await seedMemory(userId, 'preference', PHRASE_GLOBAL);

    const res = await retriever.retrieve({
      userId,
      conversationText: PHRASE_NOISE, // disjoint tokens → below threshold
      topK: 5,
    });
    expect(res).toEqual([]);
  });

  it("AC-RT5: one user never retrieves another user's memories", async () => {
    const owner = await createUser('owner');
    const intruder = await createUser('intruder');
    await seedMemory(owner, 'secret', PHRASE_GLOBAL);

    const res = await retriever.retrieve({
      userId: intruder,
      conversationText: PHRASE_GLOBAL,
      topK: 5,
    });
    expect(res).toEqual([]); // isolation: intruder sees nothing
  });

  it('scopes contact memories by the active contact; global facts always eligible', async () => {
    const userId = await createUser('contact');
    await seedMemory(userId, 'global', PHRASE_GLOBAL, null);
    await seedMemory(userId, 'contact', PHRASE_CONTACT, 'Alex');

    // Active contact Alex, querying the contact phrase → contact memory eligible.
    const withAlex = await retriever.retrieve({
      userId,
      contactLabel: 'Alex',
      conversationText: PHRASE_CONTACT,
      topK: 5,
    });
    expect(withAlex.map((m) => m.content)).toContain(PHRASE_CONTACT);

    // A different contact must NOT see Alex's contact-specific memory.
    const withSam = await retriever.retrieve({
      userId,
      contactLabel: 'Sam',
      conversationText: PHRASE_CONTACT,
      topK: 5,
    });
    expect(withSam.map((m) => m.content)).not.toContain(PHRASE_CONTACT);

    // Global facts are eligible regardless of the active contact.
    const globalHit = await retriever.retrieve({
      userId,
      contactLabel: 'Sam',
      conversationText: PHRASE_GLOBAL,
      topK: 5,
    });
    expect(globalHit.map((m) => m.content)).toContain(PHRASE_GLOBAL);
  });
});
