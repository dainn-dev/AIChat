import { Logger } from '@nestjs/common';
import { EmbeddingConfig } from '../../config/configuration';
import {
  EmbeddingProvider,
  EmbeddingResult,
} from './embedding-provider.interface';

/** Default OpenAI base URL when no proxy override (`LLM_BASE_URL`) is set. */
const DEFAULT_OPENAI_BASE_URL = 'https://api.openai.com/v1';
/** Locked working default model (DAI-146) when none is configured. */
const DEFAULT_EMBEDDING_MODEL = 'text-embedding-3-small';

/** Minimal shape of the OpenAI embeddings response we consume. */
interface EmbeddingResponse {
  data?: Array<{ embedding?: number[] }>;
  model?: string;
}

/**
 * OpenAI-compatible embedding provider (DAI-146 / MS-1), the sibling of
 * {@link OpenAiProvider}. Talks to the standard `/embeddings` API over `fetch`
 * — no SDK dependency — so it works against OpenAI directly or any
 * OpenAI-compatible gateway when `LLM_BASE_URL` is set, sharing the same
 * `LLM_API_KEY` as chat completions (epic Decision #1).
 *
 * The configured `dimensions` (N) is sent on every request so the returned
 * vector length is pinned regardless of the model's native size; a mismatch is
 * treated as a provider error (thrown) so the row stays NULL and is retried,
 * rather than persisting a wrong-width vector MS-2's `vector(N)` column rejects.
 */
export class OpenAiEmbeddingProvider implements EmbeddingProvider {
  readonly name = 'openai';
  readonly model: string;

  private readonly logger = new Logger(OpenAiEmbeddingProvider.name);
  private readonly baseUrl: string;
  private readonly apiKey: string;
  private readonly dimensions: number;
  private readonly requestTimeoutMs: number;

  constructor(cfg: EmbeddingConfig) {
    if (!cfg.apiKey) {
      // Defensive: the factory guards against this, but fail loudly rather than
      // 401-ing on the first request.
      throw new Error(
        'OpenAiEmbeddingProvider requires LLM_API_KEY to be set.',
      );
    }
    this.apiKey = cfg.apiKey;
    this.baseUrl = (cfg.baseUrl?.trim() || DEFAULT_OPENAI_BASE_URL).replace(
      /\/+$/,
      '',
    );
    this.model = cfg.model?.trim() || DEFAULT_EMBEDDING_MODEL;
    this.dimensions = cfg.dimensions;
    this.requestTimeoutMs = cfg.requestTimeoutMs;
  }

  async embed(text: string): Promise<EmbeddingResult> {
    const data = await this.post({
      model: this.model,
      input: text,
      dimensions: this.dimensions,
    });

    const vector = data.data?.[0]?.embedding;
    if (!Array.isArray(vector) || vector.length === 0) {
      throw new Error('OpenAI embeddings response contained no vector.');
    }
    if (vector.length !== this.dimensions) {
      throw new Error(
        `OpenAI returned a ${vector.length}-dim vector; expected ${this.dimensions}.`,
      );
    }

    return {
      vector,
      model: data.model || this.model,
      dimensions: vector.length,
    };
  }

  private async post(
    body: Record<string, unknown>,
  ): Promise<EmbeddingResponse> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.requestTimeoutMs);
    try {
      const res = await fetch(`${this.baseUrl}/embeddings`, {
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
          `OpenAI embeddings request failed (${res.status} ${res.statusText})${
            detail ? `: ${detail.slice(0, 500)}` : ''
          }`,
        );
      }

      return (await res.json()) as EmbeddingResponse;
    } catch (err) {
      if (err instanceof Error && err.name === 'AbortError') {
        throw new Error(
          `OpenAI embeddings request timed out after ${this.requestTimeoutMs}ms.`,
        );
      }
      throw err;
    } finally {
      clearTimeout(timer);
    }
  }
}
