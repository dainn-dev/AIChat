import { createHash } from 'crypto';
import { Injectable } from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { ConfigService } from '@nestjs/config';
import { DataSource, EntityManager } from 'typeorm';
import { MemoryConfig } from '../config/configuration';
import { EmbeddingService } from '../ai/embedding/embedding.service';
import { ExtractedFact } from './extraction/extraction-provider.interface';

export interface WriteFactsParams {
  userId: string;
  contactLabel?: string;
  facts: ExtractedFact[];
  /** Provenance written to memories.source_ref, e.g. "conversation:<id>". */
  sourceRef: string;
}

export interface WriteResult {
  written: number;
  superseded: number;
}

interface PreparedFact {
  kind: string;
  content: string;
  contactLabel: string | null;
  confidence: number;
  status: 'active' | 'pending_review';
  contentHash: string;
  embedding: string | null; // pgvector literal, or null when embedding failed
  embeddingModel: string | null;
}

/**
 * Persists extracted facts into `memories` (MS-4 / DAI-149) under the MS-2
 * schema:
 *  - content_hash idempotency: the MS-2 partial unique index
 *    (user_id, contact_label, kind, content_hash) makes re-writing the same
 *    fact a no-op (ON CONFLICT DO NOTHING) — the basis for idempotent re-runs.
 *  - latest-wins supersede (§5.3): a new high-confidence singular fact (job,
 *    birthday) retires older contradictory `active` facts of the same scope to
 *    `superseded`, so no two active facts conflict.
 *  - confidence routing (§5.9): ≥ threshold → `active`, else `pending_review`.
 * Embeds via MS-1 `embedOrNull` (never blocks the write); a NULL embedding is
 * left for the backfill to fill, and the producing model is recorded.
 */
@Injectable()
export class MemoryWriterService {
  private readonly highConfidenceThreshold: number;

  /** Kinds that hold a single truth — a new one contradicts the old (§5.3). */
  private static readonly SINGULAR_KINDS = new Set(['job', 'birthday']);

  constructor(
    @InjectDataSource() private readonly dataSource: DataSource,
    private readonly embeddings: EmbeddingService,
    config: ConfigService,
  ) {
    this.highConfidenceThreshold =
      config.get<MemoryConfig>('memory')?.highConfidenceThreshold ?? 0.7;
  }

  async writeFacts(params: WriteFactsParams): Promise<WriteResult> {
    if (params.facts.length === 0) return { written: 0, superseded: 0 };

    const prepared: PreparedFact[] = [];
    for (const f of params.facts) {
      // embedOrNull never throws; a NULL embedding is retried by the backfill.
      const embedded = await this.embeddings.embedOrNull(f.content);
      prepared.push({
        kind: f.kind,
        content: f.content,
        contactLabel:
          f.scope === 'contact' ? (params.contactLabel ?? null) : null,
        confidence: f.confidence,
        status:
          f.confidence >= this.highConfidenceThreshold
            ? 'active'
            : 'pending_review',
        contentHash: this.hash(f.content),
        embedding: embedded ? `[${embedded.vector.join(',')}]` : null,
        embeddingModel: embedded?.model ?? null,
      });
    }

    return this.dataSource.transaction(async (manager) => {
      let written = 0;
      let superseded = 0;
      for (const p of prepared) {
        if (
          p.status === 'active' &&
          MemoryWriterService.SINGULAR_KINDS.has(p.kind)
        ) {
          superseded += await this.supersedeConflicts(
            manager,
            params.userId,
            p,
          );
        }
        written += await this.insertFact(
          manager,
          params.userId,
          p,
          params.sourceRef,
        );
      }
      return { written, superseded };
    });
  }

  /** Retire active singular facts of the same scope that differ from the new one. */
  private async supersedeConflicts(
    manager: EntityManager,
    userId: string,
    p: PreparedFact,
  ): Promise<number> {
    // TypeORM returns [rows, affectedCount] for UPDATE ... RETURNING.
    const [rows]: [unknown[], number] = await manager.query(
      `UPDATE memories
          SET status = 'superseded', updated_at = now()
        WHERE user_id = $1 AND kind = $2 AND status = 'active'
          AND content_hash IS DISTINCT FROM $3
          AND COALESCE(contact_label, '') = COALESCE($4, '')
        RETURNING id`,
      [userId, p.kind, p.contentHash, p.contactLabel],
    );
    return Array.isArray(rows) ? rows.length : 0;
  }

  /** Insert one fact; ON CONFLICT on the MS-2 dedupe index makes re-runs no-ops. */
  private async insertFact(
    manager: EntityManager,
    userId: string,
    p: PreparedFact,
    sourceRef: string,
  ): Promise<number> {
    const rows: unknown[] = await manager.query(
      `INSERT INTO memories
         (user_id, contact_label, kind, content, embedding, source, source_ref,
          status, confidence, content_hash, embedding_model)
       VALUES ($1, $2, $3, $4, $5::vector, 'extracted', $6, $7, $8, $9, $10)
       ON CONFLICT (user_id, contact_label, kind, content_hash)
         WHERE content_hash IS NOT NULL
       DO NOTHING
       RETURNING id`,
      [
        userId,
        p.contactLabel,
        p.kind,
        p.content,
        p.embedding,
        sourceRef,
        p.status,
        p.confidence,
        p.contentHash,
        p.embeddingModel,
      ],
    );
    // INSERT ... RETURNING yields the rows array directly ([] on conflict).
    return Array.isArray(rows) && rows.length > 0 ? 1 : 0;
  }

  private hash(content: string): string {
    const normalized = content.toLowerCase().replace(/\s+/g, ' ').trim();
    return createHash('sha256').update(normalized).digest('hex');
  }
}
