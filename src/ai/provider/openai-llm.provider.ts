import { Logger } from '@nestjs/common';
import { LlmConfig } from '../../config/configuration';
import { AiRequestType } from '../enums/source.enum';
import {
  LlmCompletionRequest,
  LlmCompletionResult,
  LlmProvider,
} from './llm-provider.interface';

/** Default OpenAI Chat Completions endpoint, used when no proxy is configured. */
const DEFAULT_OPENAI_BASE_URL = 'https://api.openai.com/v1';
/** Sensible default model when `LLM_REPLY_MODEL` / `LLM_ANALYSIS_MODEL` are unset. */
const DEFAULT_MODEL = 'gpt-4o-mini';

/** Minimal shape of the OpenAI-compatible chat completion response we consume. */
interface ChatCompletionResponse {
  choices?: Array<{ message?: { content?: string | null } }>;
  usage?: { prompt_tokens?: number; completion_tokens?: number };
}

/**
 * OpenAI-compatible LLM provider (epic Decision #1: default to GPT/OpenAI).
 *
 * Talks to the standard `/chat/completions` endpoint via the built-in `fetch`,
 * so it works against OpenAI directly or any OpenAI-compatible gateway/proxy.
 * Routing follows the WS-4 contract:
 *   - `LLM_BASE_URL` unset → the public OpenAI endpoint (the Decision #1 default);
 *   - `LLM_BASE_URL` set    → that proxy/gateway base.
 *
 * Reply/Rewrite use `LLM_REPLY_MODEL`; Analysis uses `LLM_ANALYSIS_MODEL`
 * (both falling back to a shared default). JSON-expecting calls request
 * `response_format: json_object` so the pipeline's loose parser has clean input.
 * The key is supplied at runtime via `LLM_API_KEY` and never committed.
 */
export class OpenAiProvider implements LlmProvider {
  readonly name = 'openai';

  private readonly logger = new Logger(OpenAiProvider.name);
  private readonly apiKey: string;
  private readonly baseUrl: string;
  private readonly replyModel: string;
  private readonly analysisModel: string;
  private readonly timeoutMs: number;

  constructor(cfg: LlmConfig) {
    if (!cfg.apiKey) {
      // The factory guards against this, but fail loudly if ever constructed
      // without a key so misconfiguration surfaces at boot, not per-request.
      throw new Error('OpenAiProvider requires LLM_API_KEY to be set.');
    }
    this.apiKey = cfg.apiKey;
    // Trim a trailing slash so we can join paths predictably.
    this.baseUrl = (cfg.baseUrl?.trim() || DEFAULT_OPENAI_BASE_URL).replace(
      /\/+$/,
      '',
    );
    this.replyModel = cfg.replyModel?.trim() || DEFAULT_MODEL;
    this.analysisModel = cfg.analysisModel?.trim() || DEFAULT_MODEL;
    this.timeoutMs = cfg.requestTimeoutMs;
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
      body.response_format = { type: 'json_object' };
    }
    if (request.maxOutputTokens && request.maxOutputTokens > 0) {
      body.max_tokens = request.maxOutputTokens;
    }

    const response = await fetch(`${this.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        authorization: `Bearer ${this.apiKey}`,
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(this.timeoutMs),
    });

    if (!response.ok) {
      const detail = await response.text().catch(() => '');
      this.logger.warn(
        `OpenAI request failed (${response.status} ${response.statusText})`,
      );
      throw new Error(
        `OpenAI provider returned ${response.status}${detail ? `: ${detail.slice(0, 500)}` : ''}`,
      );
    }

    const data = (await response.json()) as ChatCompletionResponse;
    const text = data.choices?.[0]?.message?.content ?? '';

    return {
      text,
      provider: this.name,
      model,
      tokensIn: data.usage?.prompt_tokens ?? 0,
      tokensOut: data.usage?.completion_tokens ?? 0,
    };
  }

  /** Reply/Rewrite share the reply model; Analysis uses the analysis model. */
  private modelFor(type: AiRequestType): string {
    return type === AiRequestType.Analysis
      ? this.analysisModel
      : this.replyModel;
  }
}
