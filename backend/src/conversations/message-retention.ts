import { EntityManager } from 'typeorm';

/**
 * Per-conversation retention cap (security M4) for keyboard captures
 * (`ConversationsService`). Screenshot/share ingestion does not apply it yet.
 * Older messages beyond it are hard-deleted on each append (rolling window),
 * bounding storage and what a later extraction could ever read. Positions keep
 * increasing, so ordering is unaffected.
 */
export const MAX_STORED_MESSAGES_PER_CONVERSATION = 2000;

/**
 * After an append whose last message got `lastPosition`, drop messages older
 * than the newest {@link MAX_STORED_MESSAGES_PER_CONVERSATION}. Appends
 * assign contiguous positions (next = max + 1), so
 * `position <= last - cap` keeps exactly the newest `cap` messages. Must run
 * inside the append transaction.
 */
export async function enforceMessageRetention(
  manager: EntityManager,
  conversationId: string,
  lastPosition: number,
): Promise<void> {
  if (lastPosition < MAX_STORED_MESSAGES_PER_CONVERSATION) return;
  await manager.query(
    `DELETE FROM messages WHERE conversation_id = $1 AND position <= $2`,
    [conversationId, lastPosition - MAX_STORED_MESSAGES_PER_CONVERSATION],
  );
}
