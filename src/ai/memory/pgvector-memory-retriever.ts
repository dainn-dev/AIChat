import { Injectable, Logger, Optional } from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { ConfigService } from '@nestjs/config';
import { DataSource } from 'typeorm';
import { MemoryConfig } from '../../config/configuration';
import { EmbeddingService } from '../embedding/embedding.service';
import {
  MemoryQuery,
  MemoryRetriever,
  RetrievedMemory,
} from './memory-retriever.interface';

/**
 * Phase-2 vector memory retriever (MS-3 / DAI-148). Replaces the FR-P3 stub:
 * embeds the query context (MS-1) and returns the Top-K most similar memories by
 * cosine over the HNSW index (MS-2), scoped to the owner and the active contact
 * (global user facts always eligible — §5.4), filtered by a similarity
 * threshold (§5.5) and bounded to a prompt token budget.
 *
 * Read-only and defensive by contract: any failure (embedding, DB, no DB wired)
 * degrades to an empty set so the AI call never 500s (AC-RT4). Memory content is
 * treated as untrusted data and sanitized before it can reach the prompt.
 */
@Injectable()
export class PgVectorMemoryRetriever implements MemoryRetriever {
  private readonly logger = new Logger(PgVectorMemoryRetriever.name);
  private readonly defaultTopK: number;
  private readonly threshold: number;
  private readonly charBudget: number;

  /** Hard cap per memory so one row can't dominate the budget or the prompt. */
  private static readonly MAX_CONTENT_CHARS = 400;

  constructor(
    // Optional: in DB-less contexts (e.g. the AI-only e2e module) the retriever
    // is still constructed but degrades to [] rather than failing to wire.
    @Optional()
    @InjectDataSource()
    private readonly dataSource: DataSource | null,
    private readonly embeddings: EmbeddingService,
    config: ConfigService,
  ) {
    const mem = config.get<MemoryConfig>('memory');
    this.defaultTopK = mem?.retrievalTopK ?? 5;
    this.threshold = mem?.cosineThreshold ?? 0.75;
    this.charBudget = mem?.contextCharBudget ?? 1200;
  }

  async retrieve(query: MemoryQuery): Promise<RetrievedMemory[]> {
    // AC-RT5: never retrieve without an owner — no user scope means no rows.
    if (!this.dataSource || !query.userId) return [];
    const text = query.conversationText?.trim();
    if (!text) return [];

    const k = query.topK && query.topK > 0 ? query.topK : this.defaultTopK;

    try {
      // embedOrNull never throws: a provider failure degrades to [] (AC-RT4).
      const result = await this.embeddings.embedOrNull(text);
      if (!result || result.vector.length === 0) {
        return []; // nothing meaningful to match on
      }
      const literal = `[${result.vector.join(',')}]`;

      // Scope: own rows; the active contact's memories plus global user facts
      // (contact_label IS NULL), which are always eligible (§5.4). With no
      // active contact, only global facts are in scope.
      const params: unknown[] = [literal, query.userId];
      let scope: string;
      if (query.contactLabel) {
        params.push(query.contactLabel);
        scope = `(contact_label = $${params.length} OR contact_label IS NULL)`;
      } else {
        scope = `contact_label IS NULL`;
      }
      params.push(k);
      const kParam = params.length;
      params.push(this.threshold);
      const thresholdParam = params.length;

      // Inner query uses the HNSW index for ANN ordering; the outer filter drops
      // anything below the similarity threshold so noise is excluded even when
      // fewer than K rows qualify (AC-RT3). `<=>` is cosine distance ∈ [0,2];
      // similarity = 1 - distance.
      const rows: Array<{ kind: string; content: string }> =
        await this.dataSource.query(
          `SELECT kind, content FROM (
             SELECT kind, content, (embedding <=> $1::vector) AS distance
               FROM memories
              WHERE user_id = $2 AND embedding IS NOT NULL
                AND status = 'active' AND ${scope}
              ORDER BY embedding <=> $1::vector
              LIMIT $${kParam}
           ) ranked
           WHERE (1 - ranked.distance) >= $${thresholdParam}
           ORDER BY ranked.distance ASC`,
          params,
        );

      return this.applyBudget(
        rows.map((r) => ({ kind: r.kind, content: this.sanitize(r.content) })),
      );
    } catch (err) {
      // AC-RT4: retrieval is best-effort context; never propagate, never 500.
      this.logger.error(
        'Memory retrieval failed; returning empty set (AC-RT4).',
        err instanceof Error ? err.stack : String(err),
      );
      return [];
    }
  }

  /**
   * Neutralize memory text before it can reach the prompt (prompt-injection
   * guard): strip control chars/newlines, neutralize backticks, collapse
   * whitespace, and cap length. Content is reference data, never instructions.
   */
  private sanitize(content: string): string {
    const cleaned = (content ?? '')
      // eslint-disable-next-line no-control-regex
      .replace(/[\x00-\x1f]+/g, ' ') // control chars / newlines
      .replace(/`/g, "'") // neutralize code-fence/backtick injection
      .replace(/\s+/g, ' ')
      .trim();
    return cleaned.length > PgVectorMemoryRetriever.MAX_CONTENT_CHARS
      ? `${cleaned.slice(0, PgVectorMemoryRetriever.MAX_CONTENT_CHARS)}…`
      : cleaned;
  }

  /** Keep highest-ranked memories until the prompt char budget is reached. */
  private applyBudget(memories: RetrievedMemory[]): RetrievedMemory[] {
    const out: RetrievedMemory[] = [];
    let used = 0;
    for (const m of memories) {
      if (!m.content) continue;
      const cost = m.kind.length + m.content.length + 4; // "- (kind) content"
      if (out.length > 0 && used + cost > this.charBudget) break;
      out.push(m);
      used += cost;
    }
    return out;
  }
}
