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
import { ConversationErasureService } from './conversation-erasure.service';
import { retryOnceOnDeadlock } from '../common/pg-errors';
import {
  MAX_STORED_MESSAGES_PER_CONVERSATION,
  enforceMessageRetention,
} from './message-retention';
import {
  ConversationListResponse,
  DEFAULT_CONVERSATION_PAGE_SIZE,
  ListConversationsQueryDto,
} from './dto/list-conversations.dto';

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

/** Re-exported: the cap now applies to every append path (message-retention). */
export { MAX_STORED_MESSAGES_PER_CONVERSATION };

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
 * the find-or-created keyboard (`source = 'keyboard'`) conversation for
 * `(user_id, platform, contact_label)` with
 * {@link messagesToAppend}, so overlapping or scrolled-up re-captures do not
 * duplicate messages. Persistence is best-effort: every failure is logged and
 * swallowed and the caller gets `null`, so it can never fail the keyboard reply.
 *
 * Erasure (`remove`) hard-deletes the conversation, its messages (FK cascade),
 * its screenshots, and every extracted memory with no other source
 * conversation left (`memory_sources`), via the shared
 * {@link ConversationErasureService} (also used by the opt-out purge).
 */
@Injectable()
export class ConversationsService {
  private readonly logger = new Logger(ConversationsService.name);

  constructor(
    @InjectDataSource() private readonly dataSource: DataSource,
    private readonly erasure: ConversationErasureService,
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
      if (!(await this.isMemoryOptedIn(this.dataSource, capture.userId))) {
        return null;
      }

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
      // Opted out while this capture was in flight (re-checked under lock).
      if (conversationId === null) return null;

      // New messages → (debounced) background extraction. Fire-and-forget; the
      // queue swallows its own errors (AC-M4).
      if (appended > 0) {
        void this.extractionQueue?.enqueue(conversationId);
      }
      return conversationId;
    } catch (err) {
      // Log only the Postgres error code/constraint: the message, detail and
      // stack can echo parameter values such as contact labels (security L3).
      const { code, constraint } = err as {
        code?: string;
        constraint?: string;
      };
      this.logger.error(
        `Keyboard capture persistence failed for user ${capture.userId} ` +
          `(code=${code ?? 'n/a'}, constraint=${constraint ?? 'n/a'}); continuing without it.`,
      );
      return null;
    }
  }

  /**
   * `GET /conversations` — owner-scoped, newest first, metadata only (no
   * message content). Lets a client find stored conversations to erase even
   * when the reply that stored one returned `conversation_id: null`
   * (persist timeout / lost response — security H1).
   */
  async list(
    userId: string,
    query: ListConversationsQueryDto,
  ): Promise<ConversationListResponse> {
    const limit = query.limit ?? DEFAULT_CONVERSATION_PAGE_SIZE;
    const offset = query.offset ?? 0;
    const rows: Array<{
      id: string;
      platform: string;
      contact_label: string | null;
      source: string;
      message_count: string | number;
      updated_at: Date;
      total: string | number;
    }> = await this.dataSource.query(
      `SELECT c.id, c.platform, c.contact_label, c.source, c.updated_at,
              (SELECT count(*) FROM messages m WHERE m.conversation_id = c.id)
                AS message_count,
              count(*) OVER () AS total
         FROM conversations c
        WHERE c.user_id = $1
        ORDER BY c.updated_at DESC, c.id ASC
        LIMIT $2 OFFSET $3`,
      [userId, limit, offset],
    );
    const total =
      rows.length > 0
        ? Number(rows[0].total)
        : await this.countConversations(userId);
    return {
      conversations: rows.map((r) => ({
        id: r.id,
        platform: r.platform,
        contact_label: r.contact_label,
        source: r.source,
        message_count: Number(r.message_count),
        updated_at: new Date(r.updated_at).toISOString(),
      })),
      total,
      limit,
      offset,
    };
  }

  /** Total for an out-of-range page (window count needs at least one row). */
  private async countConversations(userId: string): Promise<number> {
    const rows: Array<{ total: string }> = await this.dataSource.query(
      `SELECT count(*)::text AS total FROM conversations WHERE user_id = $1`,
      [userId],
    );
    return Number(rows[0]?.total ?? 0);
  }

  /**
   * `DELETE /conversations/:id` — owner-scoped hard delete. A conversation that
   * does not exist OR belongs to another user is a 404 (no IDOR, and no
   * existence oracle). Messages and `memory_sources` links go via
   * `ON DELETE CASCADE`; screenshots and now-unsourced extracted memories are
   * removed explicitly because their FK/ref would otherwise keep the content.
   * The `FOR UPDATE` lock serializes with an in-flight extraction write, which
   * holds the row `FOR SHARE` (see MemoryWriterService). Retried once if
   * Postgres aborts the transaction as a deadlock victim.
   */
  async remove(userId: string, conversationId: string): Promise<void> {
    await retryOnceOnDeadlock(() =>
      this.dataSource.transaction(async (manager) => {
        const owned = await this.erasure.lockOwnedConversation(
          manager,
          userId,
          conversationId,
        );
        if (!owned) {
          throw new NotFoundException({
            code: 'CONVERSATION_NOT_FOUND',
            message: 'Conversation not found.',
          });
        }
        await this.erasure.eraseLocked(manager, userId, [conversationId]);
      }),
    );
  }

  /**
   * Opt-in check. Outside a transaction it is a cheap pre-check; inside the
   * capture transaction (`lock = true`) it takes the user row `FOR SHARE`,
   * which conflicts with the opt-out purge's row lock (`PATCH /me/settings`):
   * either the capture commits first and the purge then erases it, or the
   * purge commits first and the capture sees `false` and stores nothing.
   */
  private async isMemoryOptedIn(
    runner: Pick<EntityManager, 'query'>,
    userId: string,
    lock = false,
  ): Promise<boolean> {
    const rows: Array<{ memory_opt_in: boolean }> = await runner.query(
      `SELECT memory_opt_in FROM users WHERE id = $1${lock ? ' FOR SHARE' : ''}`,
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
  ): Promise<{ conversationId: string | null; appended: number }> {
    await manager.query(`SELECT pg_advisory_xact_lock(hashtext($1))`, [
      `keyboard-conv:${userId}:${platform}:${contactLabel}`,
    ]);
    // Re-check under the user row lock, before touching any conversation, so
    // a capture can never land after a committed opt-out purge.
    if (!(await this.isMemoryOptedIn(manager, userId, true))) {
      return { conversationId: null, appended: 0 };
    }

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
    // Rolling retention window (security M4): keep only the newest
    // MAX_STORED_MESSAGES_PER_CONVERSATION messages per conversation.
    await enforceMessageRetention(
      manager,
      conversationId,
      startPosition + toAppend.length - 1,
    );
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
    // Only keyboard-created conversations: a screenshot/share conversation for
    // the same contact is never merged into (conversations.source).
    const existing: Array<{ id: string }> = await manager.query(
      `SELECT id FROM conversations
        WHERE user_id = $1 AND source = 'keyboard'
          AND platform = $2 AND contact_label = $3
        ORDER BY updated_at DESC
        LIMIT 1`,
      [userId, platform, contactLabel],
    );
    if (existing.length > 0) return existing[0].id;

    const created: Array<{ id: string }> = await manager.query(
      `INSERT INTO conversations (user_id, platform, contact_label, source)
       VALUES ($1, $2, $3, 'keyboard')
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
