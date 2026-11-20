import { ExtractedFact } from './extraction/extraction-provider.interface';

/** A fact after the scope policy, ready for the writer. */
export interface PolicedFact extends ExtractedFact {
  /** Force `pending_review` regardless of confidence (user must confirm). */
  requireReview: boolean;
}

/**
 * Persistent-prompt-injection guard (Android Phase 0, security M3).
 *
 * A memory stored with `contact_label = NULL` is GLOBAL: it is retrieved into
 * every future prompt for this user, for every contact. Text written by the
 * other party ("them") must never be able to plant such a fact unreviewed, so:
 *
 * 1. a `user`-scoped fact attributed to "them" is about the contact, not the
 *    user → re-scoped to `contact`;
 * 2. any fact that would still be stored globally (`user` scope, or `contact`
 *    scope in a conversation without a contact label) is only auto-accepted
 *    when attributed to "me"; otherwise (attributed to "them" or unknown) it
 *    is stored as `pending_review`, which retrieval never surfaces until the
 *    user confirms it on the dashboard.
 *
 * Contact-scoped facts with a contact label are unchanged: they only reach
 * prompts for that same contact.
 */
export function applyFactScopePolicy(
  fact: ExtractedFact,
  contactLabel: string | undefined,
): PolicedFact {
  const scope =
    fact.scope === 'user' && fact.fromSender === 'them'
      ? 'contact'
      : fact.scope;
  const global = scope === 'user' || !contactLabel;
  return {
    ...fact,
    scope,
    requireReview: global && fact.fromSender !== 'me',
  };
}
