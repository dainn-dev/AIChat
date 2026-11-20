/**
 * Output language the keyboard explicitly asks for (Android Phase 0). The
 * keyboard toggles EN/VI and suggestions must come back in that language. When
 * a caller omits it, the prompt instructs the model to answer in the
 * conversation's own language instead.
 */
export enum OutputLang {
  En = 'en',
  Vi = 'vi',
}

/** Human-readable language names used inside prompts. */
export const OUTPUT_LANG_NAMES: Record<OutputLang, string> = {
  [OutputLang.En]: 'English',
  [OutputLang.Vi]: 'Vietnamese (tiếng Việt)',
};
