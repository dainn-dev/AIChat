import { Logger } from '@nestjs/common';
import { LlmConfig } from '../../config/configuration';
import { AiRequestType } from '../enums/source.enum';
import {
  LlmCompletionRequest,
  LlmCompletionResult,
  LlmProvider,
} from './llm-provider.interface';

/** Default OpenAI Chat Completions endpoint when no proxy override is set. */
const DEFAULT_OPENAI_BASE_URL = 'https://api.openai.com/v1';
/** Sensible, low-cost defaults when no per-call model override is configured. */
const DEFAULT_REPLY_MODEL = 'gpt-4o-mini';
const DEFAULT_ANALYSIS_MODEL = 'gpt-4o-mini';

/** Minimal shape of the OpenAI Chat Completions response we consume. */
interface ChatCompletionResponse {
  choices?: Array<{ message?: { content?: string | null } }>;
  usage?: { prompt_tokens?: number; completion_tokens?: number };
  model?: string;
}

/**
 * OpenAI-compatible LLM provider (epic Decision #1 default). Talks to the
 * standard `/chat/completions` API over `fetch`, so it works against OpenAI
 * directly or any OpenAI-compatible gateway/proxy when `LLM_BASE_URL` is set —
 * no SDK dependency, no endpoint changes in the pipeline.
 *
 * Constructed from `LlmConfig` by the provider factory in `ai.module.ts`. A
 * missing `apiKey` is a misconfiguration for this provider; the factory only
 * selects it once a key is present, falling back to the keyless stub otherwise.
 */
export class OpenAiProvider implements LlmProvider {
  readonly name = 'openai';

  private readonly logger = new Logger(OpenAiProvider.name);
  private readonly baseUrl: string;
  private readonly apiKey: string;
  private readonly replyModel: string;
  private readonly analysisModel: string;
  private readonly requestTimeoutMs: number;

  constructor(cfg: LlmConfig) {
    if (!cfg.apiKey) {
      // Defensive: the factory guards against this, but fail loudly if the
      // provider is ever constructed without a key rather than 401-ing later.
      throw new Error('OpenAiProvider requires LLM_API_KEY to be set.');
    }
    this.apiKey = cfg.apiKey;
    // Strip any trailing slash so we can join paths predictably.
    this.baseUrl = (cfg.baseUrl?.trim() || DEFAULT_OPENAI_BASE_URL).replace(
      /\/+$/,
      '',
    );
    this.replyModel = cfg.replyModel?.trim() || DEFAULT_REPLY_MODEL;
    this.analysisModel = cfg.analysisModel?.trim() || DEFAULT_ANALYSIS_MODEL;
    this.requestTimeoutMs = cfg.requestTimeoutMs;
  }

  async complete(request: LlmCompletionRequest): Promise<LlmCompletionResult> {
    const model = this.modelFor(request.type);
    const body: Record<string, unknown> = {
      model,
      messages: [
        { role: 'system', content: request.prompt.system },
        { role: 'user', content: request.prompt.user },
      ],
    };
    if (request.expectJson) {
      // Strict JSON mode; the context builder already instructs JSON output.
      body.response_format = { type: 'json_object' };
    }
    if (request.maxOutputTokens && request.maxOutputTokens > 0) {
      body.max_tokens = request.maxOutputTokens;
    }

    const data = await this.post(body);
    const text = data.choices?.[0]?.message?.content ?? '';

    return {
      text,
      provider: this.name,
      model: data.model || model,
      tokensIn: data.usage?.prompt_tokens ?? 0,
      tokensOut: data.usage?.completion_tokens ?? 0,
    };
  }

  /** Route to the reply or analysis model; rewrite reuses the reply model. */
  private modelFor(type: AiRequestType): string {
    return type === AiRequestType.Analysis
      ? this.analysisModel
      : this.replyModel;
  }

  private async post(
    body: Record<string, unknown>,
  ): Promise<ChatCompletionResponse> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.requestTimeoutMs);
    try {
      const res = await fetch(`${this.baseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          authorization: `Bearer ${this.apiKey}`,
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });

      if (!res.ok) {
        const detail = await res.text().catch(() => '');
        throw new Error(
          `OpenAI request failed (${res.status} ${res.statusText})${
            detail ? `: ${detail.slice(0, 500)}` : ''
          }`,
        );
      }

      return (await res.json()) as ChatCompletionResponse;
    } catch (err) {
      if (err instanceof Error && err.name === 'AbortError') {
        throw new Error(
          `OpenAI request timed out after ${this.requestTimeoutMs}ms.`,
        );
      }
      throw err;
    } finally {
      clearTimeout(timer);
    }
  }
}
