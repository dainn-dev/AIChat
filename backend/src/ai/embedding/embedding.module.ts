import { Logger, Module, Provider } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { EmbeddingConfig } from '../../config/configuration';
import {
  EMBEDDING_PROVIDER,
  EmbeddingProvider,
} from './embedding-provider.interface';
import { StubEmbeddingProvider } from './stub-embedding.provider';
import { OpenAiEmbeddingProvider } from './openai-embedding.provider';
import { EmbeddingService } from './embedding.service';
import { EmbeddingBackfillService } from './embedding-backfill.service';
import { EMBEDDING_BACKFILL_STORE } from './embedding-backfill.store';
import { StubEmbeddingBackfillStore } from './stub-embedding-backfill.store';

/**
 * Selects the active embedding provider from config, mirroring the LLM provider
 * factory in `ai.module.ts`: the OpenAI-compatible provider when an
 * `LLM_API_KEY` is present (shared with chat completions, epic Decision #1),
 * else the keyless deterministic stub for local/test.
 */
const embeddingProviderFactory: Provider = {
  provide: EMBEDDING_PROVIDER,
  inject: [ConfigService, StubEmbeddingProvider],
  useFactory: (
    config: ConfigService,
    stub: StubEmbeddingProvider,
  ): EmbeddingProvider => {
    const cfg = config.getOrThrow<EmbeddingConfig>('embedding');
    const logger = new Logger('EmbeddingModule');
    switch (cfg.provider) {
      case 'openai':
        if (!cfg.apiKey) {
          logger.warn(
            'EMBEDDING_PROVIDER=openai but no LLM_API_KEY set; falling back to deterministic stub.',
          );
          return stub;
        }
        return new OpenAiEmbeddingProvider(cfg);
      case 'stub':
        return stub;
      default:
        logger.warn(
          `Unknown EMBEDDING_PROVIDER "${cfg.provider}"; falling back to deterministic stub.`,
        );
        return stub;
    }
  },
};

/**
 * Phase 1 binds the no-op backfill store ([]); MS-2 swaps in the TypeORM-backed
 * store once the `memories` entity exists.
 */
const embeddingBackfillStoreFactory: Provider = {
  provide: EMBEDDING_BACKFILL_STORE,
  inject: [StubEmbeddingBackfillStore],
  useFactory: (stub: StubEmbeddingBackfillStore) => stub,
};

/**
 * Embedding service + provider abstraction (DAI-146 / MS-1). Exports
 * {@link EmbeddingService} (embed-on-write, consumed by MS-4/MS-5) and
 * {@link EmbeddingBackfillService} (re-embed/backfill, FR-E5), plus the
 * provider/store tokens so MS-2 can rebind the backfill store to real storage.
 */
@Module({
  providers: [
    StubEmbeddingProvider,
    StubEmbeddingBackfillStore,
    embeddingProviderFactory,
    embeddingBackfillStoreFactory,
    EmbeddingService,
    EmbeddingBackfillService,
  ],
  exports: [
    EmbeddingService,
    EmbeddingBackfillService,
    EMBEDDING_PROVIDER,
    EMBEDDING_BACKFILL_STORE,
  ],
})
export class EmbeddingModule {}
