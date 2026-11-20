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
  /** `requireReview` forces `pending_review` (scope policy, security M3). */
  facts: Array<ExtractedFact & { requireReview?: boolean }>;
  /** Provenance written to memories.source_ref, e.g. "conversation:<id>". */
  sourceRef: string;
  /**
   * Conversation the facts were derived from (Android Phase 0). Every written
   * OR deduplicated memory is linked to it in `memory_sources`, and the write
   * is aborted if the conversation no longer exists (or, for a keyboard
   * conversation, its owner opted out) at write time.
   */
  conversationId: string;
}

/** Why a write was dropped before touching `memories`. */
export type WriteAbortReason = 'conversation-gone' | 'opted-out';

export interface WriteResult {
  written: number;
  superseded: number;
  /** Set when the write was dropped by the in-transaction guard. */
  aborted?: WriteAbortReason;
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
 *
 * Android Phase 0: every written or deduplicated memory is linked to its
 * source conversation in `memory_sources`, and the whole write is dropped if
 * that conversation was erased (or its keyboard owner opted out) meanwhile.
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
          !f.requireReview && f.confidence >= this.highConfidenceThreshold
            ? 'active'
            : 'pending_review',
        contentHash: this.hash(f.content),
        embedding: embedded ? `[${embedded.vector.join(',')}]` : null,
        embeddingModel: embedded?.model ?? null,
      });
    }

    return this.dataSource.transaction(async (manager) => {
      const aborted = await this.guardSource(
        manager,
        params.userId,
        params.conversationId,
      );
      if (aborted) return { written: 0, superseded: 0, aborted };

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
        const { memoryId, inserted } = await this.insertFact(
          manager,
          params.userId,
          p,
          params.sourceRef,
        );
        if (inserted) written++;
        if (memoryId) {
          await this.linkSource(manager, memoryId, params.conversationId);
        }
      }
      return { written, superseded };
    });
  }

  /**
   * Race guard (Android Phase 0): re-check, inside the write transaction, that
   * the source conversation still exists and — for keyboard captures — that
   * its owner is still opted in. `FOR SHARE` holds the conversation row until
   * commit, so a concurrent `DELETE /conversations/:id` (which locks it
   * `FOR UPDATE`) either runs first (we abort) or waits for us and then erases
   * what we wrote through `memory_sources`.
   */
  private async guardSource(
    manager: EntityManager,
    userId: string,
    conversationId: string,
  ): Promise<WriteAbortReason | undefined> {
    const rows: Array<{ source: string; memory_opt_in: boolean }> =
      await manager.query(
        `SELECT c.source, u.memory_opt_in
           FROM conversations c
           JOIN users u ON u.id = c.user_id
          WHERE c.id = $1 AND c.user_id = $2
          FOR SHARE OF c`,
        [conversationId, userId],
      );
    if (rows.length === 0) return 'conversation-gone';
    if (rows[0].source === 'keyboard' && rows[0].memory_opt_in !== true) {
      return 'opted-out';
    }
    return undefined;
  }

  /** Record that `memoryId` was derived from `conversationId` (idempotent). */
  private async linkSource(
    manager: EntityManager,
    memoryId: string,
    conversationId: string,
  ): Promise<void> {
    await manager.query(
      `INSERT INTO memory_sources (memory_id, conversation_id)
       VALUES ($1, $2)
       ON CONFLICT DO NOTHING`,
      [memoryId, conversationId],
    );
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

  /**
   * Insert one fact; ON CONFLICT on the MS-2 dedupe index makes re-runs
   * no-ops. Returns the id of the inserted row, or of the existing row the
   * fact deduplicated into (so its provenance can still be linked).
   */
  private async insertFact(
    manager: EntityManager,
    userId: string,
    p: PreparedFact,
    sourceRef: string,
  ): Promise<{ memoryId: string | null; inserted: boolean }> {
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
    const insertedRows = rows as Array<{ id: string }>;
    if (Array.isArray(insertedRows) && insertedRows.length > 0) {
      return { memoryId: insertedRows[0].id, inserted: true };
    }

    // Deduplicated: find the existing row by the same dedupe key.
    const existing: Array<{ id: string }> = await manager.query(
      `SELECT id FROM memories
        WHERE user_id = $1 AND contact_label IS NOT DISTINCT FROM $2
          AND kind = $3 AND content_hash = $4`,
      [userId, p.contactLabel, p.kind, p.contentHash],
    );
    return { memoryId: existing[0]?.id ?? null, inserted: false };
  }

  private hash(content: string): string {
    const normalized = content.toLowerCase().replace(/\s+/g, ' ').trim();
    return createHash('sha256').update(normalized).digest('hex');
  }
}
