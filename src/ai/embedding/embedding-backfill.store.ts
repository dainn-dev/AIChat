/** Injection token for the active {@link EmbeddingBackfillStore} binding. */
export const EMBEDDING_BACKFILL_STORE = Symbol('EMBEDDING_BACKFILL_STORE');

/** A memory row that needs (re-)embedding: just the id and the text to embed. */
export interface PendingMemory {
  id: string;
  content: string;
}

/**
 * Persistence port the backfill depends on, so MS-1 ships without owning the
 * `memories` schema/entity (MS-2 / DAI-121). Phase 1 binds a no-op stub
 * ({@link StubEmbeddingBackfillStore}) returning `[]`; MS-2 swaps in a
 * TypeORM-backed implementation — the same interface-then-swap pattern as
 * {@link MemoryRetriever}. No backfill change is needed when the real store lands.
 */
export interface EmbeddingBackfillStore {
  /**
   * Fetch up to `limit` memories needing an embedding: rows with a NULL
   * embedding, OR whose recorded `embedding_model` differs from `targetModel`
   * (a model/dimension change — FR-E5). Ordering MUST be stable so paging across
   * batches is well-defined.
   */
  findPending(limit: number, targetModel: string): Promise<PendingMemory[]>;

  /** Persist a freshly computed vector + the model that produced it for one row. */
  saveEmbedding(id: string, vector: number[], model: string): Promise<void>;
}
