/** Minimal message identity used for overlap detection: who said what. */
export interface MergeableMessage {
  sender: string;
  content: string;
}

/**
 * Overlap-aware merge for keyboard captures (Android Phase 0).
 *
 * The Android AccessibilityService re-reads the visible chat on every keyboard
 * request, so consecutive captures overlap: the start of the new window repeats
 * the end of what is already stored. This finds the LONGEST suffix of `stored`
 * that equals a prefix of `incoming` (matching on sender + content) and returns
 * only the part of `incoming` after that overlap — the genuinely new messages.
 *
 * - Full overlap (nothing new)  → `[]`.
 * - No overlap at all           → all of `incoming` (appended as-is).
 * - Empty `stored`              → all of `incoming`.
 *
 * Pure and order-preserving; it never mutates its inputs. `stored` only needs
 * to hold the stored tail (the last `incoming.length` messages suffice, since a
 * longer suffix can never match a shorter prefix). O(n·m) with n, m ≤ 100.
 */
export function messagesToAppend<T extends MergeableMessage>(
  stored: readonly MergeableMessage[],
  incoming: readonly T[],
): T[] {
  const maxOverlap = Math.min(stored.length, incoming.length);
  for (let k = maxOverlap; k > 0; k--) {
    if (suffixEqualsPrefix(stored, incoming, k)) {
      return incoming.slice(k);
    }
  }
  return incoming.slice();
}

/** True when the last `k` of `stored` equal the first `k` of `incoming`. */
function suffixEqualsPrefix(
  stored: readonly MergeableMessage[],
  incoming: readonly MergeableMessage[],
  k: number,
): boolean {
  const offset = stored.length - k;
  for (let i = 0; i < k; i++) {
    if (!sameMessage(stored[offset + i], incoming[i])) return false;
  }
  return true;
}

function sameMessage(a: MergeableMessage, b: MergeableMessage): boolean {
  return a.sender === b.sender && a.content === b.content;
}
