/**
 * Text hygiene for user-supplied labels that are stored and later interpolated
 * into LLM prompts (Android Phase 0, security M3 / L2).
 */

/** Matches strings that contain no NUL (Postgres `text` cannot store it). */
export const NO_NUL_PATTERN = /^[^\u0000]*$/;

/** Allowed `platform` identifiers after lower-casing (security L2). */
export const PLATFORM_PATTERN = /^[a-z0-9._-]{1,64}$/;

/**
 * Neutralize a short label (e.g. `contact_label`) for prompt interpolation:
 * control characters / newlines become spaces, backticks and double quotes
 * are replaced so a label cannot open a code fence or close a quoted span,
 * whitespace is collapsed, and the result is trimmed and length-capped.
 */
export function sanitizeLabel(value: string, maxLength = 128): string {
  const cleaned = value
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u001f\u007f]+/g, ' ')
    .replace(/[`"]/g, "'")
    .replace(/\s+/g, ' ')
    .trim();
  return cleaned.length > maxLength
    ? cleaned.slice(0, maxLength).trim()
    : cleaned;
}

/**
 * class-transformer helper for label fields: sanitizes strings, but leaves a
 * NUL-containing string untouched so validation can reject it explicitly.
 */
export function sanitizeLabelInput(value: unknown, maxLength = 128): unknown {
  if (typeof value !== 'string' || value.includes('\u0000')) return value;
  return sanitizeLabel(value, maxLength);
}
