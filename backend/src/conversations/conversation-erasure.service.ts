import { Injectable } from '@nestjs/common';
import { EntityManager } from 'typeorm';

/** What a keyboard-data purge removed (`PATCH /me/settings` opt-out). */
export interface KeyboardPurgeCounts {
  conversations: number;
  memories: number;
}

/**
 * Conversations erased per statement batch during an opt-out purge. All
 * batches run in the caller's single transaction (the purge stays atomic);
 * batching only bounds the size of each statement (id arrays, cascaded
 * message deletes — at most 2,000 messages per conversation).
 */
export const PURGE_BATCH_SIZE = 100;

/**
 * Per-transaction bounds for a keyboard purge (security M2). `SET LOCAL`
 * scopes them to the purge transaction only. A user with a lot of keyboard
 * data, or a long lock wait, fails fast (the transaction rolls back, nothing
 * changes) instead of holding row locks open indefinitely.
 */
export const PURGE_LOCK_TIMEOUT_MS = 5_000;
export const PURGE_STATEMENT_TIMEOUT_MS = 30_000;

/**
 * Conversation erasure shared by `DELETE /conversations/:id`, the keyboard
 * opt-out purge (`PATCH /me/settings` with memory_opt_in false), and the
 * one-off legacy opt-out backfill (`npm run purge:legacy-optout`).
 *
 * Every method runs inside the caller's transaction (`manager`) so callers
 * own atomicity and lock order. Lock order is always
 * `users` row (opt-out only) → `conversations` rows `FOR UPDATE` → `memories`
 * rows `FOR UPDATE`, matching the extraction writer, which holds the
 * conversation `FOR SHARE` before it writes memories (MemoryWriterService),
 * and keyboard capture, which holds the user row `FOR SHARE` before it
 * touches a conversation (ConversationsService).
 *
 * Holds no state and injects nothing (not even a DataSource), so any module
 * that already runs a transaction can use it without a module cycle.
 */
@Injectable()
export class ConversationErasureService {
  /**
   * Lock one owned conversation `FOR UPDATE`. Returns false when it does not
   * exist or belongs to another user (callers map that to a 404).
   */
  async lockOwnedConversation(
    manager: EntityManager,
    userId: string,
    conversationId: string,
  ): Promise<boolean> {
    const owned: Array<{ id: string }> = await manager.query(
      `SELECT id FROM conversations WHERE id = $1 AND user_id = $2 FOR UPDATE`,
      [conversationId, userId],
    );
    return owned.length > 0;
  }

  /**
   * Apply {@link PURGE_LOCK_TIMEOUT_MS} / {@link PURGE_STATEMENT_TIMEOUT_MS}
   * to the current transaction. Call first, before taking any lock.
   */
  async applyPurgeTimeouts(manager: EntityManager): Promise<void> {
    // SET does not take bind parameters; the values are numeric constants.
    await manager.query(
      `SET LOCAL lock_timeout = '${PURGE_LOCK_TIMEOUT_MS}ms'`,
    );
    await manager.query(
      `SET LOCAL statement_timeout = '${PURGE_STATEMENT_TIMEOUT_MS}ms'`,
    );
  }

  /**
   * Opt-out purge: erase EVERY keyboard-captured conversation of the user
   * (`source = 'keyboard'`) exactly as per-conversation erasure does.
   * Screenshot/share/app conversations are untouched. The caller must already
   * hold the user row lock (so no new keyboard conversation can appear).
   *
   * All keyboard conversations are locked up front, in id order, then erased
   * in batches of {@link PURGE_BATCH_SIZE}. Batching is still exact: a memory
   * backed by conversations in two batches survives the first (its remaining
   * link is in the second) and is deleted by the second. Survivors'
   * `source_ref`s are re-pointed once, after the last batch.
   */
  async purgeKeyboardConversations(
    manager: EntityManager,
    userId: string,
  ): Promise<KeyboardPurgeCounts> {
    const locked: Array<{ id: string }> = await manager.query(
      `SELECT id FROM conversations
        WHERE user_id = $1 AND source = 'keyboard'
        ORDER BY id
        FOR UPDATE`,
      [userId],
    );
    const ids = locked.map((r) => r.id);
    if (ids.length === 0) return { conversations: 0, memories: 0 };
    let memories = 0;
    for (let i = 0; i < ids.length; i += PURGE_BATCH_SIZE) {
      memories += await this.eraseBatch(
        manager,
        userId,
        ids.slice(i, i + PURGE_BATCH_SIZE),
      );
    }
    await this.repointSourceRefs(manager, userId, ids);
    return { conversations: ids.length, memories };
  }

  /**
   * Erase conversations the caller already locked `FOR UPDATE`. Deletes:
   *  - extracted memories left with NO source outside `conversationIds`
   *    (`memory_sources`); legacy rows with no links at all fall back to
   *    their `source_ref`. Manual memories are never touched. Facts those
   *    memories superseded become active again first;
   *  - screenshots linked to the conversations (their FK is SET NULL, which
   *    would otherwise orphan the OCR text);
   *  - the conversations (messages and `memory_sources` cascade;
   *    `ai_requests` / `memory_extractions` keep a nulled, content-free link).
   * Surviving memories whose `source_ref` named an erased conversation are
   * re-pointed to a remaining source. Returns the number of memories deleted.
   */
  async eraseLocked(
    manager: EntityManager,
    userId: string,
    conversationIds: readonly string[],
  ): Promise<number> {
    if (conversationIds.length === 0) return 0;
    const deleted = await this.eraseBatch(manager, userId, conversationIds);
    await this.repointSourceRefs(manager, userId, conversationIds);
    return deleted;
  }

  /**
   * {@link eraseLocked} without the final `source_ref` re-point. A legacy
   * memory (no links) is matched on `source_ref` in the batch holding its
   * conversation, which stays correct while earlier batches' refs have not
   * been re-pointed yet.
   */
  private async eraseBatch(
    manager: EntityManager,
    userId: string,
    conversationIds: readonly string[],
  ): Promise<number> {
    const ids = [...conversationIds];
    const sourceRefs = ids.map((id) => `conversation:${id}`);

    const doomedIds = await this.lockUnsourcedMemories(
      manager,
      userId,
      ids,
      sourceRefs,
    );
    if (doomedIds.length > 0) {
      // Facts these memories superseded (latest-wins) become active again
      // rather than staying hidden forever (review MEDIUM 2).
      await manager.query(
        `UPDATE memories
            SET status = 'active', superseded_by = NULL, updated_at = now()
          WHERE user_id = $1 AND status = 'superseded'
            AND superseded_by = ANY($2::uuid[])
            AND NOT (id = ANY($2::uuid[]))`,
        [userId, doomedIds],
      );
      await manager.query(
        `DELETE FROM memories WHERE user_id = $1 AND id = ANY($2::uuid[])`,
        [userId, doomedIds],
      );
    }
    await manager.query(
      `DELETE FROM screenshots
        WHERE user_id = $1 AND conversation_id = ANY($2::uuid[])`,
      [userId, ids],
    );
    await manager.query(
      `DELETE FROM conversations WHERE user_id = $1 AND id = ANY($2::uuid[])`,
      [userId, ids],
    );
    return doomedIds.length;
  }

  /**
   * Re-point surviving memories whose `source_ref` named an erased
   * conversation to their earliest remaining source. Links to erased
   * conversations already cascaded, so any remaining link is a survivor.
   */
  private async repointSourceRefs(
    manager: EntityManager,
    userId: string,
    erasedIds: readonly string[],
  ): Promise<void> {
    const sourceRefs = erasedIds.map((id) => `conversation:${id}`);
    await manager.query(
      `UPDATE memories m
          SET source_ref = 'conversation:' || (
                SELECT s.conversation_id::text FROM memory_sources s
                 WHERE s.memory_id = m.id
                 ORDER BY s.created_at ASC
                 LIMIT 1)
        WHERE m.user_id = $1 AND m.source_ref = ANY($2::text[])
          AND EXISTS (SELECT 1 FROM memory_sources s WHERE s.memory_id = m.id)`,
      [userId, sourceRefs],
    );
  }

  /** Extracted memories with no source left once `ids` go, locked FOR UPDATE. */
  private async lockUnsourcedMemories(
    manager: EntityManager,
    userId: string,
    ids: string[],
    sourceRefs: string[],
  ): Promise<string[]> {
    const rows: Array<{ id: string }> = await manager.query(
      `SELECT m.id FROM memories m
        WHERE m.user_id = $1
          AND m.source = 'extracted'
          AND (
            EXISTS (SELECT 1 FROM memory_sources s
                     WHERE s.memory_id = m.id
                       AND s.conversation_id = ANY($2::uuid[]))
            OR (m.source_ref = ANY($3::text[])
                AND NOT EXISTS (SELECT 1 FROM memory_sources s
                                 WHERE s.memory_id = m.id))
          )
          AND NOT EXISTS (SELECT 1 FROM memory_sources s
                           WHERE s.memory_id = m.id
                             AND NOT (s.conversation_id = ANY($2::uuid[])))
        FOR UPDATE`,
      [userId, ids, sourceRefs],
    );
    return rows.map((r) => r.id);
  }
}
