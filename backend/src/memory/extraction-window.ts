/**
 * Extraction cost bounds (Android Phase 0, security M4). Only the most recent
 * part of a conversation is sent to the extractor, so a very long (or
 * attacker-inflated) conversation cannot drive unbounded LLM/embedding spend.
 */
export const EXTRACTION_MAX_MESSAGES = 200;
export const EXTRACTION_MAX_CHARS = 20_000;

/**
 * Keep the most recent messages (input is oldest → newest) within both caps,
 * dropping from the oldest end. Always keeps at least the newest message,
 * truncated to `maxChars` if it alone exceeds the budget. Pure.
 */
export function boundExtractionWindow<
  T extends { sender: string; content: string },
>(
  messages: readonly T[],
  maxMessages = EXTRACTION_MAX_MESSAGES,
  maxChars = EXTRACTION_MAX_CHARS,
): T[] {
  const recent = messages.slice(-maxMessages);
  const out: T[] = [];
  let used = 0;
  for (let i = recent.length - 1; i >= 0; i--) {
    const m = recent[i];
    if (used + m.content.length > maxChars) {
      if (out.length === 0) {
        out.push({ ...m, content: m.content.slice(-maxChars) });
      }
      break;
    }
    out.push(m);
    used += m.content.length;
  }
  return out.reverse();
}
