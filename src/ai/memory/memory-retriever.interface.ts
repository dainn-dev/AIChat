/** Injection token for the active {@link MemoryRetriever} binding. */
export const MEMORY_RETRIEVER = Symbol('MEMORY_RETRIEVER');

export interface RetrievedMemory {
  kind: string;
  content: string;
}

export interface MemoryQuery {
  userId?: string;
  contactLabel?: string;
  /** Normalized conversation text used for relevance in Phase 2. */
  conversationText: string;
  topK: number;
}

/**
 * Stable retrieval interface for the Memory Engine. In Phase 1 the engine is
 * not built (Phase 2 / DAI-121), so the bound implementation is a no-op that
 * returns `[]` (DAI-124 §1.2 FR-P3). The pipeline depends only on this
 * interface, so Phase 2 swaps in the real vector retriever without any
 * pipeline change.
 */
export interface MemoryRetriever {
  retrieve(query: MemoryQuery): Promise<RetrievedMemory[]>;
}
