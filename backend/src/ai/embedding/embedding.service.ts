import { Inject, Injectable, Logger } from '@nestjs/common';
import {
  EMBEDDING_PROVIDER,
  EmbeddingProvider,
  EmbeddingResult,
} from './embedding-provider.interface';

/**
 * Reusable embedding service (DAI-146 / MS-1) — the single entry point every
 * write path uses to embed memory content. It wraps the configured
 * {@link EmbeddingProvider} and owns the resilience contract:
 *
 * - {@link embed} surfaces provider errors (throws) for callers that want them.
 * - {@link embedOrNull} is the embed-on-write primitive (FR-E1/AC-E2): it never
 *   throws, returning `null` on provider failure so the caller can persist the
 *   memory with `embedding = NULL` and let the backfill (FR-E5) retry later. The
 *   memory is never lost and the write is never blocked.
 *
 * The concrete provider (OpenAI vs keyless stub) is bound in
 * {@link EmbeddingModule}; this service is provider-agnostic. Consumed by MS-4
 * (extraction) and MS-5 (dashboard create/edit, including the re-embed on edit,
 * AC-E3) and by {@link EmbeddingBackfillService}.
 */
@Injectable()
export class EmbeddingService {
  private readonly logger = new Logger(EmbeddingService.name);

  constructor(
    @Inject(EMBEDDING_PROVIDER) private readonly provider: EmbeddingProvider,
  ) {}

  /** Configured target model, recorded on rows and used for backfill staleness. */
  get model(): string {
    return this.provider.model;
  }

  /**
   * Embed `text`, throwing on provider failure. Prefer {@link embedOrNull} on
   * the write path; use this only when the caller intends to handle the error.
   */
  async embed(text: string): Promise<EmbeddingResult> {
    return this.provider.embed(this.prepare(text));
  }

  /**
   * Resilient embed-on-write: returns the embedding, or `null` if the provider
   * fails or the input is empty after trimming. Never throws — the caller
   * persists `null` as `embedding = NULL` (retryable) and the backfill fills it.
   */
  async embedOrNull(text: string): Promise<EmbeddingResult | null> {
    const input = this.prepare(text);
    if (!input) return null;
    try {
      return await this.provider.embed(input);
    } catch (err) {
      this.logger.warn(
        `Embedding failed (provider=${this.provider.name}); storing NULL for retry: ${
          err instanceof Error ? err.message : String(err)
        }`,
      );
      return null;
    }
  }

  /** Collapse whitespace and trim; empty content is not embeddable. */
  private prepare(text: string): string {
    return (text ?? '').replace(/\s+/g, ' ').trim();
  }
}
