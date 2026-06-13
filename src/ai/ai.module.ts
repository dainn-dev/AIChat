import { Logger, Module, Provider } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { LlmConfig } from '../config/configuration';
import { AiController } from './ai.controller';
import { AiService } from './ai.service';
import { AiPipelineService } from './pipeline/ai-pipeline.service';
import { Normalizer } from './pipeline/normalizer';
import { ContextBuilder } from './pipeline/context-builder';
import { OutputValidator } from './validation/output-validator';
import { AiRequestLogger } from './logging/ai-request-logger.service';
import { LLM_PROVIDER, LlmProvider } from './provider/llm-provider.interface';
import { StubLlmProvider } from './provider/stub-llm.provider';
import {
  MEMORY_RETRIEVER,
  MemoryRetriever,
} from './memory/memory-retriever.interface';
import { StubMemoryRetriever } from './memory/stub-memory-retriever';

/**
 * Selects the active LLM provider from config (`LLM_PROVIDER` env). Phase 1
 * ships only the keyless `stub`; the concrete provider is gated on epic
 * Decision #1. When that lands, add the concrete class (e.g. ClaudeProvider)
 * and a case here — no other file changes.
 */
const llmProviderFactory: Provider = {
  provide: LLM_PROVIDER,
  inject: [ConfigService, StubLlmProvider],
  useFactory: (config: ConfigService, stub: StubLlmProvider): LlmProvider => {
    const cfg = config.getOrThrow<LlmConfig>('llm');
    const logger = new Logger('AiModule');
    switch (cfg.provider) {
      case 'stub':
        return stub;
      // case 'claude': return new ClaudeProvider(cfg);   // Decision #1
      // case 'openai': return new OpenAiProvider(cfg);
      // case 'gemini': return new GeminiProvider(cfg);
      default:
        logger.warn(
          `Unknown LLM_PROVIDER "${cfg.provider}"; falling back to deterministic stub.`,
        );
        return stub;
    }
  },
};

/** Phase 1 binds the no-op memory retriever ([]); Phase 2 (DAI-121) swaps it. */
const memoryRetrieverFactory: Provider = {
  provide: MEMORY_RETRIEVER,
  inject: [StubMemoryRetriever],
  useFactory: (stub: StubMemoryRetriever): MemoryRetriever => stub,
};

@Module({
  controllers: [AiController],
  providers: [
    AiService,
    AiPipelineService,
    Normalizer,
    ContextBuilder,
    OutputValidator,
    AiRequestLogger,
    StubLlmProvider,
    StubMemoryRetriever,
    llmProviderFactory,
    memoryRetrieverFactory,
  ],
  exports: [AiPipelineService],
})
export class AiModule {}
