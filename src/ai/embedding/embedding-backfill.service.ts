import { Inject, Injectable, Logger } from '@nestjs/common';
import { EmbeddingService } from './embedding.service';
import {
  EMBEDDING_BACKFILL_STORE,
  EmbeddingBackfillStore,
} from './embedding-backfill.store';

/** Default rows fetched + embedded per batch. */
const DEFAULT_BATCH_SIZE = 100;
/** Safety cap on batches per run; prevents an unbounded loop. */
const DEFAULT_MAX_BATCHES = 10_000;

export interface BackfillOptions {
  batchSize?: number;
  maxBatches?: number;
}

export interface BackfillReport {
  /** Rows fetched and attempted this run. */
  scanned: number;
  /** Rows successfully embedded and persisted. */
  embedded: number;
  /** Rows whose embedding failed; left NULL for a future run. */
  failed: number;
  /** Batches processed. */
  batches: number;
  /** Target model the run embedded against. */
  model: string;
}

/**
 * Backfill / re-embed job (DAI-146 / MS-1, FR-E5). Runnable and idempotent: it
 * walks {@link EmbeddingBackfillStore.findPending} in batches — rows with a NULL
 * embedding (AC-E2 retry) or a stale `embedding_model` (a model/dimension
 * change) — embeds each via {@link EmbeddingService.embedOrNull}, and persists
 * the result. Recording the model on every vector is what makes a future N
 * change a no-op re-run.
 *
 * Idempotency + termination:
 * - A successful row gets a non-null embedding tagged with the target model, so
 *   it stops matching `findPending` on the next fetch.
 * - A failed row stays NULL and is retried on the next run.
 * - Within a single run, rows already attempted are tracked; if a fetch returns
 *   only already-seen ids (e.g. the provider is down and nothing progressed),
 *   the run stops rather than spinning — the next invocation retries them.
 */
@Injectable()
export class EmbeddingBackfillService {
  private readonly logger = new Logger(EmbeddingBackfillService.name);

  constructor(
    private readonly embeddings: EmbeddingService,
    @Inject(EMBEDDING_BACKFILL_STORE)
    private readonly store: EmbeddingBackfillStore,
  ) {}

  async run(options: BackfillOptions = {}): Promise<BackfillReport> {
    const batchSize = Math.max(1, options.batchSize ?? DEFAULT_BATCH_SIZE);
    const maxBatches = Math.max(1, options.maxBatches ?? DEFAULT_MAX_BATCHES);
    const model = this.embeddings.model;

    const report: BackfillReport = {
      scanned: 0,
      embedded: 0,
      failed: 0,
      batches: 0,
      model,
    };
    const attempted = new Set<string>();

    while (report.batches < maxBatches) {
      const pending = await this.store.findPending(batchSize, model);
      if (pending.length === 0) break;

      // Drop rows already tried this run; if nothing is new, we are not making
      // forward progress (persistent failures), so stop and let a later run retry.
      const fresh = pending.filter((m) => !attempted.has(m.id));
      if (fresh.length === 0) break;

      for (const memory of fresh) {
        attempted.add(memory.id);
        report.scanned++;
        const result = await this.embeddings.embedOrNull(memory.content);
        if (result) {
          await this.store.saveEmbedding(
            memory.id,
            result.vector,
            result.model,
          );
          report.embedded++;
        } else {
          // Leave NULL; idempotent retry on a subsequent run.
          report.failed++;
        }
      }
      report.batches++;
    }

    this.logger.log(
      `Backfill complete (model=${model}): scanned=${report.scanned} embedded=${report.embedded} failed=${report.failed} batches=${report.batches}`,
    );
    return report;
  }
}
