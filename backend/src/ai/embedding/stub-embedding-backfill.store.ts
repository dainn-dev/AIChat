import { Injectable } from '@nestjs/common';
import {
  EmbeddingBackfillStore,
  PendingMemory,
} from './embedding-backfill.store';

/**
 * Phase-1 no-op backfill store. Reports nothing pending and ignores writes, so
 * {@link EmbeddingBackfillService} is wired and runnable today (it simply does
 * no work). MS-2 replaces this binding with the TypeORM-backed store once the
 * `memories` entity exists — mirrors {@link StubMemoryRetriever}.
 */
@Injectable()
export class StubEmbeddingBackfillStore implements EmbeddingBackfillStore {
  async findPending(): Promise<PendingMemory[]> {
    return [];
  }

  async saveEmbedding(): Promise<void> {
    // No-op until the real persistence layer (MS-2) is bound.
  }
}
