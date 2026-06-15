import { DynamicModule, Global, Logger, Module, Provider } from '@nestjs/common';
import { BullModule } from '@nestjs/bullmq';
import { ConfigService } from '@nestjs/config';
import { LlmConfig, RedisConfig } from '../config/configuration';
import { EmbeddingModule } from '../ai/embedding/embedding.module';
import {
  MEMORY_EXTRACTION_PROVIDER,
  MemoryExtractionProvider,
} from './extraction/extraction-provider.interface';
import { StubMemoryExtractionProvider } from './extraction/stub-extraction.provider';
import { OpenAiMemoryExtractionProvider } from './extraction/openai-extraction.provider';
import { MemoryWriterService } from './memory-writer.service';
import { MemoryExtractionService } from './memory-extraction.service';
import { SensitiveDataFilter } from './privacy/sensitive-data.filter';
import {
  BullMemoryExtractionQueue,
  MEMORY_EXTRACTION_QUEUE,
  MEMORY_EXTRACTION_QUEUE_NAME,
  NoopMemoryExtractionQueue,
} from './memory-queue';
import { MemoryExtractionProcessor } from './memory-extraction.processor';

/** Selects the LLM-backed extractor when a key is set, else the keyless stub. */
const extractionProviderFactory: Provider = {
  provide: MEMORY_EXTRACTION_PROVIDER,
  inject: [ConfigService, StubMemoryExtractionProvider],
  useFactory: (
    config: ConfigService,
    stub: StubMemoryExtractionProvider,
  ): MemoryExtractionProvider => {
    const llm = config.getOrThrow<LlmConfig>('llm');
    if (llm.provider === 'openai' && llm.apiKey) {
      return new OpenAiMemoryExtractionProvider(llm);
    }
    return stub;
  },
};

const isExtractionEnabled = (): boolean =>
  ['1', 'true', 'yes', 'on'].includes(
    (process.env.MEMORY_EXTRACTION_ENABLED ?? 'false').toLowerCase(),
  );

/**
 * Memory Engine write side (MS-4 / DAI-149). Global so conversation-write paths
 * can inject the queue. The extraction service/writer/provider/filter are always
 * wired (and unit/DB-testable without Redis); the BullMQ queue + worker are added
 * only when `MEMORY_EXTRACTION_ENABLED` is set (it needs Redis), otherwise a
 * no-op queue keeps callers wiring-complete.
 */
@Global()
@Module({})
export class MemoryModule {
  static register(): DynamicModule {
    const enabled = isExtractionEnabled();
    const baseProviders: Provider[] = [
      StubMemoryExtractionProvider,
      extractionProviderFactory,
      SensitiveDataFilter,
      MemoryWriterService,
      MemoryExtractionService,
    ];

    if (!enabled) {
      new Logger('MemoryModule').log(
        'Memory extraction disabled (set MEMORY_EXTRACTION_ENABLED=true with Redis to enable).',
      );
      return {
        module: MemoryModule,
        imports: [EmbeddingModule],
        providers: [
          ...baseProviders,
          {
            provide: MEMORY_EXTRACTION_QUEUE,
            useClass: NoopMemoryExtractionQueue,
          },
        ],
        exports: [MEMORY_EXTRACTION_QUEUE, MemoryExtractionService],
      };
    }

    return {
      module: MemoryModule,
      imports: [
        EmbeddingModule,
        BullModule.forRootAsync({
          inject: [ConfigService],
          useFactory: (config: ConfigService) => ({
            connection: config.getOrThrow<RedisConfig>('redis'),
          }),
        }),
        BullModule.registerQueue({ name: MEMORY_EXTRACTION_QUEUE_NAME }),
      ],
      providers: [
        ...baseProviders,
        BullMemoryExtractionQueue,
        {
          provide: MEMORY_EXTRACTION_QUEUE,
          useExisting: BullMemoryExtractionQueue,
        },
        MemoryExtractionProcessor,
      ],
      exports: [MEMORY_EXTRACTION_QUEUE, MemoryExtractionService],
    };
  }
}
