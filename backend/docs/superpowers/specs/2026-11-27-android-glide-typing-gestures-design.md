# Gboard-like Glide Typing (incl. Vietnamese) and Gestures — Design

**Date:** 2026-11-27
**Surface:** Android keyboard app (`mobile/android`, FlorisBoard fork)
**Status:** Draft for approval (builder check done: nothing open)

## Intent

Users should be able to glide-type (swipe through letters to type a whole word) and use key gestures the way they do in
Gboard. The core users type Vietnamese with Telex, so glide must work in **Vietnamese**, not only English. A swipe over
the plain letters `d-u-o-c` must produce **"được"**, with the alternatives ("đuốc", "dược", …) in the suggestion bar,
and the previous syllable must steer which diacritics win ("không được" vs "thuốc dược").

## Current state (what exists)

- FlorisBoard's glide typing is in the tree and works for English. The code is
  `ime/text/gestures/GlideTypingManager`, `StatisticalGlideTypingClassifier` and `GlideTypingGesture`, with the trail
  and the preview. It is **off by default** (`prefs.glide.enabled = false`).
- The glide word list comes from `nlpManager.getListOfWords(subtype)`. For latin subtypes that is
  `LatinLanguageProvider`, which loads one **English-only** `assets/ime/dict/data.json` (word → frequency 0..255) for
  every subtype. It has no Vietnamese words.
- The classifier builds each word's ideal path from `keysByCharacter`. Letters with no key of their own (`ư`, `đ`, `ộ`,
  …) have no position, so every Vietnamese word with diacritics is unusable today.
- Swipe gestures (`AppPrefs.Gestures`, `SwipeAction`) work, and the settings screen `GesturesScreen` is reachable from
  Settings. Defaults:
  - swipe up → SHIFT
  - swipe down → HIDE_KEYBOARD
  - swipe left/right → SWITCH_TO_NEXT/PREV_SUBTYPE
  - space bar swipe left/right → move cursor
  - delete swipe left → DELETE_CHARACTERS_PRECISELY
- Vietnamese input is AIChat's own `com.aichat.keyboard.vi` (`ViInputComposer`, `ViInputController`, `ViInputGate`).
  It offers Telex or VNI and a **tone style** setting: MODERN ("hoà", "thuỷ") or CLASSIC ("hòa", "thủy"), defined
  in the `vi-input` module (`com.aichat.vi.ToneStyle`). The default subtypes
  (`AiChatDefaultSubtypes`) are Vietnamese (first, the default) and English, both on QWERTY.

## Goals

1. **Glide on by default** for both the Vietnamese and English subtypes, with the trail and the live preview on.
   Existing users get it on as well. This is a default change, so it applies only if the user never changed the
   setting.
2. **Vietnamese glide, one syllable per swipe**, like Gboard. On lift, the best syllable is committed with an automatic
   trailing space, and up to the remaining suggestions are shown in the smartbar. Tapping one replaces the committed
   syllable.
3. **Diacritic restoration.** Matching runs on the diacritic-free key sequence ("folded" form: tones and marks removed,
   `đ`→`d`). Each folded form expands to its real syllables, ranked by **unigram frequency × context from the previous
   syllable (bigram)**. With no previous syllable, or an unseen pair, ranking falls back to the unigram.
4. **Output follows the user's settings:**
   - tone placement per `ViInputSettings.toneStyle` (MODERN or CLASSIC);
   - case per the shift state (`KeyboardManager.fixCase`);
   - NFC-normalized text.
5. **Telex coexistence.** Starting a glide first finishes any in-progress Telex composition, so no half-composed
   syllable is left. The glided syllable is committed as plain text and never passes through Telex, so `dd` or `ow`
   rules never fire on it. Tapping keys after a glide composes normally.
6. **Gboard-like gestures:**
   - When glide is on, the four letter-area swipes (up, down, left, right) default to NO_ACTION, because glide owns
     swipes on letters.
   - Space bar swipe left/right keeps moving the cursor (unchanged).
   - Delete key swipe left defaults to **word-wise select-then-delete**: words are highlighted as the finger moves and
     deleted on release. This is the existing DELETE_WORDS_PRECISELY or SELECT_WORDS_PRECISELY behavior; the builder
     verifies which one gives Gboard's feel.
   - Backspace right after a glide deletes the whole glided syllable (the existing
     `glide.immediateBackspaceDeletesWord`, on).
7. **English unchanged in method.** The English subtype keeps the existing classifier and dictionary, gaining only the
   default changes.

## Non-goals

- Multi-syllable "word" glide in one swipe (e.g. swiping a whole "cảm ơn"). Gboard does one syllable per swipe, and so
  do we.
- Next-word prediction while tapping, AI-based diacritic choice, and any network call.
- Learning from the user's typing (a personal dictionary) in this round.
- New layouts (Telex/QWERTY only) and VNI-specific glide behavior, beyond VNI users getting the same Vietnamese glide.
- Rewriting FlorisBoard's English glide classifier.

## Anti-goals (failure even if it "works")

- Glide makes normal tap typing worse: taps misread as glides, Telex broken after a glide, or extra latency on taps.
- Lag after lifting the finger. The committed syllable must appear without a noticeable delay on a mid-range phone,
  with a budget of about 100 ms from lift to commit.
- Output in the wrong tone style, or decomposed (NFD) text, which breaks search in chat apps.
- Glide active where Telex is deliberately off: passwords, email and URL fields (`ViInputGate.BLOCKED_VARIATIONS`).
  Glide must be off in password fields in all subtypes.
- Keystrokes, swiped words or user text leaving the device, or appearing in logs.
- An APK size jump beyond the data budget below.

## Constraints

- Fully on-device and offline.
- **Data budget:** Vietnamese glide data adds at most about 3 MB to the APK, compressed. Memory at runtime stays
  within a few MB, and the data loads lazily, off the main thread, the first time a Vietnamese glide happens or the
  subtype activates.
- The data must have a license compatible with shipping in a closed-source commercial app (Wikipedia-derived counts,
  see "Decisions on data and mixed language").
  The app's other-licenses screen must carry any required attribution.
- Follow existing patterns: pure Kotlin logic with JVM unit tests (no Robolectric in the project), Android types only
  at the edges, `prefs` via `AppPrefs`, and strings in `aichat_strings.xml` en and vi.
- Do not break FlorisBoard upstream structure more than needed. Vietnamese-specific code goes under
  `com.aichat.keyboard.vi` (or a `vi/glide` subpackage), with minimal hooks in FlorisBoard classes.

## Approaches considered

| | Approach | Trade-off |
|-|----------|-----------|
| A | Syllable dictionary with diacritic folding; rank accented variants by unigram frequency | Small data (hundreds of KB), fast, reuses the classifier. But it ignores context, so "dược phẩm" often comes out "được phẩm" and needs a manual fix. |
| **B** | **A plus previous-syllable bigram context for ranking accented variants** | Closest to Gboard; getting the diacritics right is what makes glide usable in Vietnamese. Costs a few MB of pair data and needs a licensable source. |
| C | Ask the backend LLM to pick diacritics | Rejected: network latency at swipe time, does not work offline, and sends what the user types off the device. |

**Chosen: B** (user's decision, 2026-11-27).

## Decided behavior

### Data (built offline, shipped as assets)

- A build-time tool in the repo (`mobile/android/tools/vi-glide-data/`) produces the assets from a corpus. It is
  re-runnable and documented, and its output is committed. It produces:
  - a **syllable unigram table**: each valid Vietnamese syllable (NFC, one canonical tone style) with its frequency;
  - a **bigram table**: (previous syllable, syllable) → frequency, pruned to fit the budget (e.g. pairs above a
    count threshold, top-N per previous syllable).
  - The **folded form** is computed at build time or load time. Folding removes tone marks and the ă/â/ê/ô/ơ/ư marks,
    maps `đ`→`d`, and lowercases. Example: "được" → "duoc", "Ở" → "o".
- Only syllables that pass a Vietnamese syllable-validity check are kept, which drops typos and foreign tokens from the
  corpus.
- A compact format is the builder's choice (e.g. a sorted binary or a gzip'd TSV parsed once), within the budget.

### Matching and ranking (Vietnamese subtype)

1. Gesture points go to the existing statistical classifier, but over the set of **distinct folded forms** (a few
   thousand) instead of raw words. The classifier's frequency input for a folded form is the sum of its syllables'
   unigram frequencies.
2. The classifier returns the top-K folded forms, with K chosen by the builder (about 5).
3. Each folded form expands to its syllables. The score of syllable s is
   `classifierScore(fold(s)) × P(s | previous syllable)`, where P is smoothed: bigram when the pair is known, else the
   unigram. The previous syllable is the last syllable before the cursor in the editor. Without one (start of field,
   after punctuation or a line break), the unigram is used.
4. Top result → committed. The next up to 8 → smartbar candidates.
5. The tone style conversion (MODERN ↔ CLASSIC) is applied to every output, and case comes from the shift state.

### English subtype

Unchanged algorithm and data. The Vietnamese pipeline is selected by the subtype's composer (the vi-input composer),
not by locale string matching. The English subtype never sees Vietnamese candidates.

### Commit and editing

- On glide start (the gesture is recognized as a glide, not on touch-down): finish the active Telex composition.
- On lift, commit the syllable, then a space (Gboard's auto-space). The builder decides whether the next tapped
  punctuation removes that auto-space, as Gboard does for ". , ? !".
- Tapping a smartbar candidate replaces the last glided syllable, keeping the space.
- Backspace immediately after a glide deletes the glided syllable (the existing pref, default on).
- Glide is suppressed in fields where `ViInputGate` blocks composing (password, email, URL) and in all password fields
  for every subtype.

### Defaults and settings

- `glide.enabled` default → true. `glide.showTrail` and `glide.showPreview` stay true.
- When glide is enabled, the four letter-area swipe defaults become NO_ACTION.
  - Users who customized a swipe keep their choice.
  - The builder ensures the defaults resolve correctly whether glide is on or off. Example: keep the old defaults when
    the user turns glide off, or simply make NO_ACTION the default; the builder picks and documents which.
- Delete swipe left default → the word-wise select-then-delete action (see Goal 6).
- The existing Gestures settings screen stays the place to change all of this. A short "Vuốt để gõ" (Glide typing)
  toggle should be easy to find. It already exists in FlorisBoard's screen, so the builder confirms it is visible in
  AIChat's settings.

## Testing expectations

- JVM unit tests (pure Kotlin):
  - folding (all tones and marks, đ, case, NFC input);
  - tone-style conversion both ways on tricky syllables ("hoà/hòa", "thuý/thúy", "qua", "gia");
  - the syllable validity check;
  - the ranking with bigram context and the unigram fallback ("không" + duoc → được; "thuốc" + duoc → dược);
  - the expansion from folded forms;
  - the previous-syllable extraction from editor text (punctuation, line start, multiple spaces);
  - the commit decision logic (auto-space, replace on candidate tap).
- Classifier integration on the JVM if `StatisticalGlideTypingClassifier` can be driven without Android: synthetic
  gestures over a fixed QWERTY key geometry for "duoc", "khong", "nguoi" should rank the expected folded forms in the
  top 3. Otherwise, add this as an instrumented or manual check.
- Telex regression: the existing `ViInputControllerTest` stays green, plus a test that a glide commit after a partial
  Telex composition finishes it cleanly (via `FakeViEditor`).
- Data tool: a test or self-check that the output stays within budget and every entry is a valid NFC syllable.
- Manual device checks:
  - glide common phrases ("không được", "cảm ơn bạn", "người ta", "thuốc dược"), with the result in the right tone
    style;
  - glide in a password field is off;
  - taps right after a glide compose Telex correctly;
  - latency feels instant;
  - swipe-delete by words.

## Decisions on data and mixed language (user, 2026-11-27)

1. **Data source: Vietnamese Wikipedia.** The data tool computes our own syllable unigram and bigram counts from a
   Vietnamese Wikipedia text dump (CC BY-SA). Only the derived counts ship. Attribution ("Contains statistics derived
   from Wikipedia, CC BY-SA") goes in the app's licenses screen. GPL word lists (Viet74K) and non-open frequency tables
   are not used. Known limitation: Wikipedia is formal, so chat words ("nhé", "ạ", "ko") may rank lower than in real
   chat. The tool must make adding a second corpus later a config change, not a rewrite.
2. **English words in Vietnamese glide: yes.** The Vietnamese subtype also matches the top ~2,000 English words from
   the existing `data.json`. Their weight is lower than Vietnamese syllables (a multiplier constant, left to the
   builder), and they are never tone-converted. An English word wins only when its gesture match is clearly better.
   The tests must include a case where a folded Vietnamese syllable that equals an English word (e.g. "the" → "thể")
   prefers the Vietnamese syllable after a Vietnamese previous syllable.

## Left to the builder

- The exact data format, the pruning thresholds and the smoothing constant, within the budget.
- K (folded candidates) and the candidate count (≤ 8).
- How to hook the Vietnamese pipeline into `GlideTypingManager`/`NlpManager` with minimal upstream changes. For
  example, a Vietnamese `SuggestionProvider` with its own `getListOfWords`/`getFrequencyForWord` over folded forms,
  plus a post-step that expands and ranks, or a wrapper classifier.
- Whether DELETE_WORDS_PRECISELY or SELECT_WORDS_PRECISELY best matches Gboard's delete swipe.
- The auto-space and punctuation interplay details.
