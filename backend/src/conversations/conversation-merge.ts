/** Minimal message identity used for overlap detection: who said what. */
export interface MergeableMessage {
  sender: string;
  content: string;
}

/**
 * Smallest window treated as "distinctive" enough for the containment checks
 * below. A 1–2 message window (e.g. a lone "ok" or "haha") recurs naturally in
 * any chat, so finding it somewhere in history says nothing about whether it
 * is a re-capture; for such windows only the suffix/prefix overlap is used.
 */
export const MIN_CONTAINMENT_WINDOW = 3;

/**
 * Overlap-aware merge for keyboard captures (Android Phase 0).
 *
 * The Android AccessibilityService re-reads the visible chat on every keyboard
 * request, so captures overlap what is already stored. Given the stored
 * history (oldest → newest) and the incoming window, returns only the messages
 * to append. Matching is on sender + content; callers normalize (trim) first.
 *
 * Heuristic, applied in order:
 *
 * 1. **Normal case** — the longest suffix of `stored` that equals a prefix of
 *    the window → if it is non-empty, append the remainder after it (may be
 *    `[]`). This runs FIRST so a genuinely new message is never dropped just
 *    because the window also occurs earlier in history (e.g. stored
 *    `[a,b,c,x,a,b]` + window `[a,b,c]` appends `c`).
 * 2. **Scrolled up inside history** — no overlap, and the whole window (≥
 *    {@link MIN_CONTAINMENT_WINDOW} messages) occurs contiguously anywhere in
 *    `stored` → append nothing.
 * 3. **Scrolled above the stored start** — no suffix/prefix overlap, but the
 *    window's last {@link MIN_CONTAINMENT_WINDOW} messages occur in `stored`:
 *    the window ends in already-stored history, so everything before that is
 *    OLDER than what we have and cannot be appended in order → append nothing.
 * 4. **No overlap** → append the whole window.
 *
 * Known limits (by design): a window shorter than the threshold is only
 * de-duplicated via (1); a scrolled-up window whose FIRST messages happen to
 * equal the end of history (e.g. a lone "ok") is treated as the normal case
 * and its remainder appended; a window made of ≥ 3 identical messages that already
 * occurs in history is treated as a re-capture; and a window whose start
 * matches mid-history but whose end is new (history diverged, e.g. a message
 * was unsent) falls through to (4) and may duplicate its older part.
 *
 * Pure and order-preserving; never mutates its inputs. `stored` may be a
 * bounded recent slice of history — containment beyond it is not detected.
 */
export function messagesToAppend<T extends MergeableMessage>(
  stored: readonly MergeableMessage[],
  incoming: readonly T[],
): T[] {
  if (incoming.length === 0) return [];

  const overlap = longestSuffixPrefixOverlap(stored, incoming);
  if (overlap > 0) return incoming.slice(overlap);

  if (
    incoming.length >= MIN_CONTAINMENT_WINDOW &&
    containsRun(stored, incoming)
  ) {
    return [];
  }

  if (
    incoming.length > MIN_CONTAINMENT_WINDOW &&
    containsRun(stored, incoming.slice(-MIN_CONTAINMENT_WINDOW))
  ) {
    return [];
  }

  return incoming.slice();
}

/** Largest k such that the last k of `stored` equal the first k of `incoming`. */
export function longestSuffixPrefixOverlap(
  stored: readonly MergeableMessage[],
  incoming: readonly MergeableMessage[],
): number {
  const max = Math.min(stored.length, incoming.length);
  for (let k = max; k > 0; k--) {
    if (runEquals(stored, stored.length - k, incoming, 0, k)) return k;
  }
  return 0;
}

/** True when `run` occurs as a contiguous subsequence of `history`. */
export function containsRun(
  history: readonly MergeableMessage[],
  run: readonly MergeableMessage[],
): boolean {
  if (run.length === 0) return true;
  for (let start = 0; start + run.length <= history.length; start++) {
    if (runEquals(history, start, run, 0, run.length)) return true;
  }
  return false;
}

function runEquals(
  a: readonly MergeableMessage[],
  aStart: number,
  b: readonly MergeableMessage[],
  bStart: number,
  length: number,
): boolean {
  for (let i = 0; i < length; i++) {
    const x = a[aStart + i];
    const y = b[bStart + i];
    if (x.sender !== y.sender || x.content !== y.content) return false;
  }
  return true;
}
