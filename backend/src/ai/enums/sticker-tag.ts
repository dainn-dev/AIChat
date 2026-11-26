/**
 * Fixed sticker tag vocabulary for keyboard reply sticker suggestions
 * (`POST /v1/keyboard/reply` → `sticker_tags`). The LLM may only pick from this
 * list; anything else is dropped by `OutputValidator.toStickerTags`.
 *
 * MUST stay in sync with the Android `StickerTag` wire names in
 * `mobile/android/app/src/main/kotlin/com/aichat/keyboard/sticker/StickerTag.kt`.
 * Adding a tag is backwards compatible (older clients ignore unknown tags);
 * renaming or removing one is not.
 */
export const STICKER_TAGS = [
  'thanks',
  'ok',
  'laugh',
  'love',
  'wait',
  'great',
  'sorry',
  'goodnight',
  'coming',
  'yes',
  'no',
  'congrats',
  'eat',
  'hello',
  'bye',
  'sad',
  'angry',
  'surprised',
  'cheer',
  'busy',
  'miss_you',
  'thinking',
  'cool',
  'please',
] as const;

export type StickerTag = (typeof STICKER_TAGS)[number];

/** Upper bound on `sticker_tags` returned per reply. */
export const MAX_STICKER_TAGS = 3;

const STICKER_TAG_SET: ReadonlySet<string> = new Set(STICKER_TAGS);

export function isStickerTag(value: string): value is StickerTag {
  return STICKER_TAG_SET.has(value);
}
