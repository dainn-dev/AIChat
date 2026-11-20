import {
  Inject,
  Injectable,
  Logger,
  NotFoundException,
  Optional,
} from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { DataSource, EntityManager } from 'typeorm';
import { MEMORY_EXTRACTION_QUEUE, MemoryQueue } from '../memory/memory-queue';
import { MergeableMessage, messagesToAppend } from './conversation-merge';

/** One keyboard (AccessibilityService) capture of the visible chat. */
export interface KeyboardCapture {
  userId: string;
  platform?: string;
  contactLabel?: string;
  /** Per-request override; only an explicit `false` disables persistence. */
  remember?: boolean;
  messages: readonly MergeableMessage[];
}

/**
 * How many of the most recent stored messages the merge compares against. A
 * scrolled-up window older than this is not recognised as already stored.
 */
export const MERGE_HISTORY_LIMIT = 500;

interface StoredMessageRow {
  sender: string;
  content: string;
  position: number;
}

/**
 * Conversation persistence + erasure (Android Phase 0).
 *
 * Keyboard captures are stored ONLY when the user opted into memory
 * (`users.memory_opt_in`), the request names both `platform` and
 * `contact_label`, and `remember` is not `false`. Each capture is merged into
 * the find-or-created `(user_id, platform, contact_label)` conversation with
 * {@link messagesToAppend}, so overlapping or scrolled-up re-captures do not
 * duplicate messages. Persistence is best-effort: every failure is logged and swallowed
 * and the caller gets `null`, so it can never fail the keyboard reply.
 *
 * Erasure (`remove`) hard-deletes the conversation, its messages (FK cascade),
 * its screenshots, and the memories extracted from it (`source_ref`).
 */
@Injectable()
export class ConversationsService {
  private readonly logger = new Logger(ConversationsService.name);

  constructor(
    @InjectDataSource() private readonly dataSource: DataSource,
    // Optional: a no-op binding is used when extraction is disabled (MS-4).
    @Optional()
    @Inject(MEMORY_EXTRACTION_QUEUE)
    private readonly extractionQueue?: MemoryQueue,
  ) {}

  /**
   * Persist a keyboard capture if allowed. Returns the conversation id, or
   * `null` when gated off, when there is nothing to store, or on any error.
   * Never throws.
   */
  async persistKeyboardCapture(
    capture: KeyboardCapture,
  ): Promise<string | null> {
    const { platform, contactLabel } = capture;
    if (capture.remember === false || !platform || !contactLabel) {
      return null;
    }
    const messages = this.clean(capture.messages);
    if (messages.length === 0) return null;

    try {
      if (!(await this.isMemoryOptedIn(capture.userId))) return null;

      const { conversationId, appended } = await this.dataSource.transaction(
        (manager) =>
          this.mergeCapture(
            manager,
            capture.userId,
            platform,
            contactLabel,
            messages,
          ),
      );

      // New messages → (debounced) background extraction. Fire-and-forget; the
      // queue swallows its own errors (AC-M4).
      if (appended > 0) {
        void this.extractionQueue?.enqueue(conversationId);
      }
      return conversationId;
    } catch (err) {
      this.logger.error(
        `Keyboard capture persistence failed for user ${capture.userId}; continuing without it.`,
        err instanceof Error ? err.stack : String(err),
      );
      return null;
    }
  }

  /**
   * `DELETE /conversations/:id` — owner-scoped hard delete. A conversation that
   * does not exist OR belongs to another user is a 404 (no IDOR, and no
   * existence oracle). Messages go via `ON DELETE CASCADE`; screenshots and
   * extracted memories are removed explicitly because their FK/ref would
   * otherwise keep the content around.
   */
  async remove(userId: string, conversationId: string): Promise<void> {
    await this.dataSource.transaction(async (manager) => {
      const owned: Array<{ id: string }> = await manager.query(
        `SELECT id FROM conversations WHERE id = $1 AND user_id = $2 FOR UPDATE`,
        [conversationId, userId],
      );
      if (owned.length === 0) {
        throw new NotFoundException({
          code: 'CONVERSATION_NOT_FOUND',
          message: 'Conversation not found.',
        });
      }

      // Memories extracted from this conversation (MS-4 writes
      // source_ref = 'conversation:<id>').
      await manager.query(
        `DELETE FROM memories WHERE user_id = $1 AND source_ref = $2`,
        [userId, `conversation:${conversationId}`],
      );
      // screenshots.conversation_id is ON DELETE SET NULL, which would orphan
      // the stored OCR text — remove those rows instead.
      await manager.query(
        `DELETE FROM screenshots WHERE user_id = $1 AND conversation_id = $2`,
        [userId, conversationId],
      );
      // messages cascade; ai_requests / memory_extractions are SET NULL
      // (content-free audit rows).
      await manager.query(
        `DELETE FROM conversations WHERE id = $1 AND user_id = $2`,
        [conversationId, userId],
      );
    });
  }

  private async isMemoryOptedIn(userId: string): Promise<boolean> {
    const rows: Array<{ memory_opt_in: boolean }> = await this.dataSource.query(
      `SELECT memory_opt_in FROM users WHERE id = $1`,
      [userId],
    );
    return rows.length > 0 && rows[0].memory_opt_in === true;
  }

  /**
   * Find-or-create the conversation and append the non-overlapping remainder.
   * A transaction-scoped advisory lock on (user, platform, contact) serializes
   * concurrent captures for the same chat, so two in-flight requests can
   * neither create duplicate conversations nor append the same messages twice.
   */
  private async mergeCapture(
    manager: EntityManager,
    userId: string,
    platform: string,
    contactLabel: string,
    messages: MergeableMessage[],
  ): Promise<{ conversationId: string; appended: number }> {
    await manager.query(`SELECT pg_advisory_xact_lock(hashtext($1))`, [
      `keyboard-conv:${userId}:${platform}:${contactLabel}`,
    ]);

    const conversationId = await this.findOrCreateConversation(
      manager,
      userId,
      platform,
      contactLabel,
    );

    // Recent history (bounded) — enough for the suffix/prefix overlap and for
    // detecting a scrolled-up window that is already stored.
    const historyDesc: StoredMessageRow[] = await manager.query(
      `SELECT sender, content, position FROM messages
        WHERE conversation_id = $1
        ORDER BY position DESC
        LIMIT $2`,
      [conversationId, MERGE_HISTORY_LIMIT],
    );
    const history = [...historyDesc].reverse();
    const toAppend = messagesToAppend(history, messages);
    if (toAppend.length === 0) {
      return { conversationId, appended: 0 };
    }

    const startPosition =
      historyDesc.length > 0 ? Number(historyDesc[0].position) + 1 : 0;
    await this.insertMessages(manager, conversationId, toAppend, startPosition);
    await manager.query(
      `UPDATE conversations SET updated_at = now() WHERE id = $1`,
      [conversationId],
    );
    return { conversationId, appended: toAppend.length };
  }

  private async findOrCreateConversation(
    manager: EntityManager,
    userId: string,
    platform: string,
    contactLabel: string,
  ): Promise<string> {
    const existing: Array<{ id: string }> = await manager.query(
      `SELECT id FROM conversations
        WHERE user_id = $1 AND platform = $2 AND contact_label = $3
        ORDER BY updated_at DESC
        LIMIT 1`,
      [userId, platform, contactLabel],
    );
    if (existing.length > 0) return existing[0].id;

    const created: Array<{ id: string }> = await manager.query(
      `INSERT INTO conversations (user_id, platform, contact_label)
       VALUES ($1, $2, $3)
       RETURNING id`,
      [userId, platform, contactLabel],
    );
    return created[0].id;
  }

  private async insertMessages(
    manager: EntityManager,
    conversationId: string,
    messages: readonly MergeableMessage[],
    startPosition: number,
  ): Promise<void> {
    const values: string[] = [];
    const params: unknown[] = [];
    messages.forEach((m, i) => {
      const base = i * 4;
      values.push(
        `($${base + 1}, $${base + 2}, $${base + 3}, $${base + 4}, 'keyboard')`,
      );
      params.push(conversationId, m.sender, m.content, startPosition + i);
    });
    await manager.query(
      `INSERT INTO messages (conversation_id, sender, content, position, source)
       VALUES ${values.join(', ')}`,
      params,
    );
  }

  /** Trim content and drop blank turns so overlap matching is stable. */
  private clean(messages: readonly MergeableMessage[]): MergeableMessage[] {
    return messages
      .map((m) => ({ sender: m.sender, content: m.content.trim() }))
      .filter((m) => m.content.length > 0);
  }
}
