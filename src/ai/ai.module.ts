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
 * Selects the active LLM provider from config. Per epic Decision #1 the default
 * is the OpenAI-compatible provider whenever an `LLM_API_KEY` is present — see
 * `configuration.ts`, which resolves an unset `LLM_PROVIDER` to `openai` (key
 * present) or `stub` (keyless). `LLM_BASE_URL` repoints the OpenAI client at a
 * proxy/gateway without changing the provider. The keyless deterministic `stub`
 * remains for local/test runs; `claude`/`gemini` stay placeholders for now.
 */
const llmProviderFactory: Provider = {
  provide: LLM_PROVIDER,
  inject: [ConfigService, StubLlmProvider],
  useFactory: (config: ConfigService, stub: StubLlmProvider): LlmProvider => {
    const cfg = config.getOrThrow<LlmConfig>('llm');
    const logger = new Logger('AiModule');
    switch (cfg.provider) {
      case 'openai':
        if (!cfg.apiKey) {
          logger.warn(
            'LLM_PROVIDER=openai but no LLM_API_KEY set; falling back to deterministic stub.',
          );
          return stub;
        }
        return new OpenAiProvider(cfg);
      case 'stub':
        return stub;
      // case 'claude': return new ClaudeProvider(cfg);   // Decision #1
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
