import { EmbeddingBackfillService } from './embedding-backfill.service';
import { EmbeddingService } from './embedding.service';
import {
  EmbeddingBackfillStore,
  PendingMemory,
} from './embedding-backfill.store';

interface Row {
  id: string;
  content: string;
  embedding: number[] | null;
  embeddingModel: string | null;
}

/** In-memory store that models NULL/stale-model pending semantics + stable order. */
class FakeStore implements EmbeddingBackfillStore {
  saveCalls = 0;
  constructor(private readonly rows: Row[]) {}

  async findPending(
    limit: number,
    targetModel: string,
  ): Promise<PendingMemory[]> {
    return this.rows
      .filter((r) => r.embedding === null || r.embeddingModel !== targetModel)
      .slice(0, limit)
      .map((r) => ({ id: r.id, content: r.content }));
  }

  async saveEmbedding(
    id: string,
    vector: number[],
    model: string,
  ): Promise<void> {
    this.saveCalls++;
    const row = this.rows.find((r) => r.id === id);
    if (row) {
      row.embedding = vector;
      row.embeddingModel = model;
    }
  }

  pendingCount(targetModel: string): number {
    return this.rows.filter(
      (r) => r.embedding === null || r.embeddingModel !== targetModel,
    ).length;
  }
}

function service(
  embedImpl: (
    text: string,
  ) => Promise<{ vector: number[]; model: string } | null>,
  model = 'm1',
): EmbeddingService {
  return {
    model,
    embedOrNull: jest.fn(async (text: string) => {
      const r = await embedImpl(text);
      return r ? { ...r, dimensions: r.vector.length } : null;
    }),
  } as unknown as EmbeddingService;
}

const ok = async () => ({ vector: [1, 2, 3], model: 'm1' });

describe('EmbeddingBackfillService', () => {
  it('embeds all NULL rows and records the model (FR-E5)', async () => {
    const store = new FakeStore([
      { id: 'a', content: 'a', embedding: null, embeddingModel: null },
      { id: 'b', content: 'b', embedding: null, embeddingModel: null },
    ]);
    const backfill = new EmbeddingBackfillService(service(ok), store);

    const report = await backfill.run({ batchSize: 10 });

    expect(report).toMatchObject({ scanned: 2, embedded: 2, failed: 0 });
    expect(store.pendingCount('m1')).toBe(0);
  });

  it('is idempotent — a second run does no work once rows are embedded', async () => {
    const store = new FakeStore([
      { id: 'a', content: 'a', embedding: null, embeddingModel: null },
    ]);
    const backfill = new EmbeddingBackfillService(service(ok), store);

    await backfill.run({ batchSize: 10 });
    const second = await backfill.run({ batchSize: 10 });

    expect(second).toMatchObject({ scanned: 0, embedded: 0, batches: 0 });
    expect(store.saveCalls).toBe(1);
  });

  it('pages across multiple batches', async () => {
    const rows: Row[] = Array.from({ length: 5 }, (_, i) => ({
      id: String(i),
      content: `c${i}`,
      embedding: null,
      embeddingModel: null,
    }));
    const store = new FakeStore(rows);
    const backfill = new EmbeddingBackfillService(service(ok), store);

    const report = await backfill.run({ batchSize: 2 });

    expect(report.embedded).toBe(5);
    expect(report.batches).toBe(3);
    expect(store.pendingCount('m1')).toBe(0);
  });

  it('leaves failed rows NULL for a later run and terminates (no infinite loop)', async () => {
    const store = new FakeStore([
      { id: 'a', content: 'a', embedding: null, embeddingModel: null },
    ]);
    const backfill = new EmbeddingBackfillService(
      service(async () => null),
      store,
    );

    const report = await backfill.run({ batchSize: 10 });

    expect(report).toMatchObject({ scanned: 1, embedded: 0, failed: 1 });
    expect(store.pendingCount('m1')).toBe(1); // still NULL, retried next run
  });

  it('re-embeds rows whose recorded model is stale (model/N change)', async () => {
    const store = new FakeStore([
      { id: 'a', content: 'a', embedding: [9], embeddingModel: 'old-model' },
    ]);
    const backfill = new EmbeddingBackfillService(service(ok, 'm1'), store);

    const report = await backfill.run({ batchSize: 10 });

    expect(report.embedded).toBe(1);
    expect(store.pendingCount('m1')).toBe(0);
  });
});
