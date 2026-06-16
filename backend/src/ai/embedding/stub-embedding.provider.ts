import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { EmbeddingConfig } from '../../config/configuration';
import {
  EmbeddingProvider,
  EmbeddingResult,
} from './embedding-provider.interface';

/** Stable model tag recorded on stub-generated vectors. */
const STUB_MODEL = 'stub-embedding-v1';

/**
 * Deterministic, keyless embedding provider used until a real key is supplied
 * (epic Decision #1), the sibling of {@link StubLlmProvider}. It produces a
 * unit-normalized vector of the configured dimension N derived solely from the
 * input text, so:
 *
 * - the same text always embeds to the same vector (idempotent backfill, stable
 *   tests), and
 * - similar texts are NOT meaningfully near each other — semantic quality is
 *   intentionally out of scope; this exists to exercise the embed-on-write,
 *   persistence and backfill paths end-to-end with zero external dependencies.
 */
@Injectable()
export class StubEmbeddingProvider implements EmbeddingProvider {
  readonly name = 'stub';
  readonly model = STUB_MODEL;

  private readonly dimensions: number;

  constructor(config: ConfigService) {
    this.dimensions =
      config.getOrThrow<EmbeddingConfig>('embedding').dimensions;
  }

  async embed(text: string): Promise<EmbeddingResult> {
    const vector = this.deterministicVector(text);
    return { vector, model: this.model, dimensions: vector.length };
  }

  /**
   * Build an N-dim vector from the text and L2-normalize it, so cosine
   * distance behaves on stub data exactly as it will on real embeddings.
   */
  private deterministicVector(text: string): number[] {
    const raw = new Array<number>(this.dimensions);
    let norm = 0;
    for (let i = 0; i < this.dimensions; i++) {
      // Hash (text, position) into a stable value in [-1, 1).
      const h = this.hash(`${text}#${i}`);
      const v = (h % 2000) / 1000 - 1;
      raw[i] = v;
      norm += v * v;
    }
    norm = Math.sqrt(norm) || 1;
    return raw.map((v) => v / norm);
  }

  /** Small deterministic non-cryptographic hash (djb2), matching the LLM stub. */
  private hash(input: string): number {
    let h = 5381;
    for (let i = 0; i < input.length; i++) {
      h = (h * 33) ^ input.charCodeAt(i);
    }
    return Math.abs(h);
  }
}
