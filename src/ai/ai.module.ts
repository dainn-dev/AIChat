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
import { OpenAiProvider } from './provider/openai-llm.provider';
import {
  MEMORY_RETRIEVER,
  MemoryRetriever,
} from './memory/memory-retriever.interface';
import { StubMemoryRetriever } from './memory/stub-memory-retriever';

/**
 * Selects the active LLM provider from config (`LLM_PROVIDER` env). Epic
 * Decision #1 is resolved: the OpenAI-compatible `openai` provider is the
 * default whenever a key is present (see `configuration.ts`), routing to the
 * public OpenAI endpoint or, when `LLM_BASE_URL` is set, to that proxy/gateway.
 * The keyless deterministic `stub` remains the fallback for local/test runs so
 * the suite stays green without a real key. Adding another concrete provider
 * (Claude/Gemini) is a new case here plus a class — no other file changes.
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
      case 'openai':
        if (!cfg.apiKey) {
          logger.warn(
            'LLM_PROVIDER=openai but LLM_API_KEY is unset; falling back to deterministic stub.',
          );
          return stub;
        }
        return new OpenAiProvider(cfg);
      // case 'claude': return new ClaudeProvider(cfg);   // future provider
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
