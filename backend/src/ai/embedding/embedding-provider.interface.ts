/** Injection token for the active {@link EmbeddingProvider} binding. */
export const EMBEDDING_PROVIDER = Symbol('EMBEDDING_PROVIDER');

/**
 * One embedded text: the dense vector plus the model that produced it. `model`
 * is recorded alongside the vector on the memory row (AC-E1) so a later
 * model/dimension change is detectable and the backfill (FR-E5) can re-embed
 * only stale rows.
 */
export interface EmbeddingResult {
  /** Dense embedding; `vector.length` equals the configured dimension N. */
  vector: number[];
  /** Concrete model that produced the vector (e.g. `text-embedding-3-small`). */
  model: string;
  /** N — `vector.length`, mirrored here for convenience. */
  dimensions: number;
}

/**
 * Provider-abstraction interface for embeddings (DAI-146 / MS-1), parallel to
 * {@link LlmProvider}. Every concrete provider — the OpenAI-compatible default
 * or the keyless deterministic stub — implements `embed`, so the embedding
 * backend is swappable behind config (`EMBEDDING_PROVIDER` / shared
 * `LLM_API_KEY`) without touching callers.
 *
 * `embed` THROWS on provider failure by design; the resilience contract
 * (AC-E2: never block the caller, never lose the memory) lives one layer up in
 * {@link EmbeddingService.embedOrNull}, which turns a throw into a `null` so the
 * row can be stored with `embedding = NULL` and retried by the backfill.
 */
export interface EmbeddingProvider {
  readonly name: string;
  /** Configured target model, recorded on rows and used for staleness checks. */
  readonly model: string;
  embed(text: string): Promise<EmbeddingResult>;
}
