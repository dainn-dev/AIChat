# Gboard-like Glide Typing and Gestures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn on Gboard-like glide typing and gestures. This includes Vietnamese syllable glide: a swipe over plain letters yields the right diacritics, ranked by the previous syllable. The ranking uses on-device data derived from Vietnamese Wikipedia, with the top 2,000 English words mixed in.

**Architecture:**
- A stdlib-only Python tool turns a Vietnamese Wikipedia dump into a gzip'd unigram/bigram TSV asset.
- In the app, a pure-Kotlin `ViGlideLexicon` loads it, canonicalizes tone style and folds syllables to their key sequence. `ViGlideRanker` expands the classifier's top folded forms into accented syllables using bigram context.
- FlorisBoard's existing `StatisticalGlideTypingClassifier` gets a small `GlideLexicon` hook, so on the Vietnamese subtype it matches folded forms instead of the English list.
- The commit path (`EditorInstance.commitGesture`) already finishes the Telex word and applies phantom space (the Gboard-style auto-space). Tapping a candidate replaces the composing glided word. Both are reused unchanged.
- Gesture changes are preference default changes only.

**Tech Stack:** Kotlin (app and the `vi-input` JVM module), Python 3 stdlib (data tool, `unittest`), JUnit 5 + kotlin.test.

**Spec:** `backend/docs/superpowers/specs/2026-11-27-android-glide-typing-gestures-design.md` (approved 2026-11-27).

Paths are relative to `mobile/android/`. `app/…/vi/glide/` means `app/src/main/kotlin/com/aichat/keyboard/vi/glide/` (package `com.aichat.keyboard.vi.glide`). `app-test/…/vi/glide/` means `app/src/test/kotlin/com/aichat/keyboard/vi/glide/`. Run Gradle from `mobile/android`. Work on branch `feat/glide-typing`, created from `main`.

## Global Constraints

- Fully on-device and offline. No network, and no typed or swiped text in logs.
- The Vietnamese glide asset adds **≤ 3 MB** to the APK, compressed. It loads lazily, off the main thread.
- The data source is a Vietnamese Wikipedia dump (CC BY-SA), and only the derived counts ship. Add attribution "Contains statistics derived from Wikipedia (vi.wikipedia.org), CC BY-SA 4.0" to the app's licenses screen. Do not use GPL word lists (Viet74K) or non-open frequency tables.
- One syllable per swipe on the Vietnamese subtype. The Vietnamese pipeline is selected by the subtype's composer (`ViInputComposer` / vi-input), not by locale strings. The English subtype keeps its algorithm and data.
- Ranking: `score(s) = 0.5^r × P(s | prev)`, where r is the 0-based rank of `fold(s)` among the classifier's top **K = 5** folded forms. `P(s | prev) = (c(prev, s) + 5 · Pu(s)) / (c(prev) + 5)`, where `Pu(s) = count(s) / totalCount`. With no previous syllable, `P = Pu(s)`. At most **9** results: the first is committed, and the rest (≤ 8) go to the smartbar.
- English words: the top **2,000** words of `assets/ime/dict/data.json` by frequency, a–z only, at weight `ENGLISH_WEIGHT = 0.3` relative to Vietnamese. They are never tone-converted.
- Output tone style follows `ViInputSettings.toneStyle`: `ToneStyle.MODERN` = "hoà/thuỷ/khoẻ", `ToneStyle.CLASSIC` = "hòa/thủy/khỏe". Case follows `KeyboardManager.fixCase`. Output is NFC.
- Glide is off in password fields (this already exists) and in `ViInputGate.BLOCKED_VARIATIONS` fields (email, URL) for **all** subtypes.
- Gesture defaults:
  - `glide.enabled = true`;
  - letter-area `swipeUp`, `swipeDown`, `swipeLeft` and `swipeRight` = `SwipeAction.NO_ACTION`;
  - `deleteKeySwipeLeft = SwipeAction.DELETE_WORDS_PRECISELY`;
  - space-bar swipes unchanged.
  
  Users who changed a setting keep their value. This is a default-only change.
- Vietnamese-specific code goes under `com.aichat.keyboard.vi.glide` or the `vi-input` module. FlorisBoard files get minimal hooks marked `// AIChat MODIFIED`.

## Review Focus

- **The previous syllable is not a word.** Examples: a number ("2 duoc"), an emoji, an English word ("ok duoc") or a URL before the cursor. The ranker must fall back to the unigram, not crash or pick nonsense. Owner: Task 5, test `previous syllable ignores non-Vietnamese tokens`.
- **Mixed case and Shift.** A capitalized previous syllable ("Không duoc") must still match its bigram. Owner: Task 5, test `previous syllable is lowercased`. The output case comes from `fixCase` (Task 6).
- **Glide before the lexicon finishes loading.** The first glide right after switching to Vietnamese must neither block the main thread nor commit English words. It commits nothing until the lexicon is ready. Owner: Task 6, test `vietnamese lexicon not loaded means no Vietnamese suggestions and no English fallback`.
- **Folded collisions with English.** "the", "an" and "hoa" are both an English word and a folded Vietnamese syllable. After a Vietnamese previous syllable, Vietnamese wins. Owner: Task 5, test `english word loses to vietnamese syllable after vietnamese context`.
- **Corpus noise.** Wikipedia contains foreign names, numbers, markup leftovers and single letters. Only valid Vietnamese syllables (`ViSyllable.isValid`) and the English list may appear as candidates. Owner: Task 4, test `invalid syllables are dropped at load`.

---

### Task 1: Folding and tone-style conversion in `vi-input`

**Files:**
- Create: `vi-input/src/main/kotlin/com/aichat/vi/ViText.kt`
- Test: `vi-input/src/test/kotlin/com/aichat/vi/ViTextTest.kt`

**Interfaces:**
- Produces, as `public object ViText` (explicit API mode is on in this module):
  - `fun fold(word: String): String`: lowercase, NFC first, all tones and marks removed, `đ` → `d`. It returns only a–z for Vietnamese letters and leaves other characters as they are.
  - `fun withToneStyle(syllable: String, style: ToneStyle): String`: moves the tone mark to the position the style prescribes, keeping case and other letters. Use the same placement rules as `BambooEngine` (`classicTwoVowelTarget` and related); extract a shared internal helper rather than duplicating the rules. Strings that are not valid syllables are returned unchanged.

- [ ] **Step 1: Write failing tests:**
  - `fold removes tones and marks`: "được" → "duoc", "Người" → "nguoi", "ĐẤY" → "day", "ở" → "o", "khoẻ" → "khoe".
  - `fold handles NFD input`: the NFD form of "được" → "duoc".
  - `fold leaves ascii untouched`: "ok" → "ok".
  - `withToneStyle modern`: "hòa" → "hoà", "thủy" → "thuỷ", "khỏe" → "khoẻ".
  - `withToneStyle classic`: "hoà" → "hòa", "thuỷ" → "thủy".
  - `withToneStyle keeps closed and qu gi syllables`: "hoàn", "quý", "già" and "giữa" stay unchanged in both styles.
  - `withToneStyle keeps case`: "Hoà" → "Hòa" (CLASSIC).
  - `withToneStyle leaves invalid input`: "abcxyz" is unchanged.
- [ ] **Step 2: Run** `./gradlew :vi-input:test --tests '*ViTextTest'`. Expected: FAIL (unresolved `ViText`).
- [ ] **Step 3: Implement `ViText`** as specified.
- [ ] **Step 4: Run** `./gradlew :vi-input:check`. Expected: PASS, including the existing Telex, VNI and Bamboo parity tests.
- [ ] **Step 5: Commit** with `git commit -m "feat(vi-input): fold and tone-style conversion helpers"`.

### Task 2: Corpus-to-asset data tool

**Files:**
- Create: `tools/vi-glide-data/build_vi_glide_data.py`
- Create: `tools/vi-glide-data/test_build_vi_glide_data.py`
- Create: `tools/vi-glide-data/README.md`: how to download the dump, the command, the output, the licence note, and how to add a second corpus.

**Interfaces:**
- Produces the CLI `python build_vi_glide_data.py --input <file>... --output <path.tsv.gz> [--min-unigram 5] [--min-bigram 3] [--top-per-prev 40] [--max-bytes 3000000]`.
  - Inputs are `*.xml.bz2` MediaWiki dumps (stream-parsed with `bz2` + `xml.etree.ElementTree.iterparse`, taking `<text>`) or plain `*.txt` files.
  - Passing several inputs sums their counts. That is how a second corpus is added later.
- The output is a UTF-8 gzip'd TSV:
  - `U\t<syllable>\t<count>` lines, sorted by count descending;
  - then `B\t<prev>\t<syllable>\t<count>` lines;
  - syllables NFC and lowercased.
- Python functions, used by the tests:
  - `strip_wiki_markup(text: str) -> str`: removes `{{…}}`, `<ref>…</ref>`, tags, tables, `[[File:…]]` and URLs; `[[a|b]]` → `b`.
  - `tokenize(text: str) -> list[list[str]]`: returns runs of syllables. A run breaks at any character that is not a Vietnamese letter or a space (punctuation, digits, newline). Tokens are lowercased NFC. A token is kept only if it is 1–7 letters from the Vietnamese alphabet.
  - `count(runs) -> (Counter, Counter)`
  - `write_asset(unigrams, bigrams, path, min_unigram, min_bigram, top_per_prev, max_bytes) -> int`: returns the bytes written. If the result is over `max_bytes`, it raises with a message naming the thresholds to raise.

- [ ] **Step 1: Write failing `unittest` tests:**
  - `test_strip_markup`: "Hà [[Nội|Hà Nội]] {{cite}}<ref>x</ref> là" → "Hà Hà Nội  là". Compare after whitespace normalisation.
  - `test_tokenize_breaks_runs_at_punctuation_and_digits`: "Không được, 2 người ta." → `[["không","được"],["người","ta"]]`.
  - `test_tokenize_drops_non_vietnamese`: "Wifi xịn" → `[["xịn"]]`, because `w` and `f` are not Vietnamese letters, and a dropped token also breaks the run. Tokens made only of Vietnamese letters, like "iphone", are kept here; the app's `ViSyllable.isValid` filter drops them later (Task 4).
  - `test_count_bigrams_within_runs_only`
  - `test_write_asset_format_and_pruning`: unigrams below the minimum and bigrams below the minimum are dropped, and `top_per_prev` is respected.
  - `test_write_asset_rejects_over_budget`
  - `test_dump_parsing`: parses a tiny bz2 MediaWiki XML fixture built in the test.
- [ ] **Step 2: Run** `python -m unittest discover tools/vi-glide-data`. Expected: FAIL (module not found).
- [ ] **Step 3: Implement the tool** with the stdlib only. Keep memory bounded by streaming pages.
- [ ] **Step 4: Run the same command.** Expected: PASS.
- [ ] **Step 5: Commit** with `git commit -m "feat(tools): Vietnamese glide data builder from Wikipedia dumps"`.

### Task 3: Generate and ship the asset, with attribution

**Files:**
- Create: `app/src/main/assets/ime/dict/vi_glide.tsv.gz` (generated)
- Modify: the app's open-source licenses / attributions screen data. Locate it by searching `assets/` and `res/` for the existing third-party notices, e.g. "Apache" or "licenses". Add the Wikipedia line from Global Constraints.
- Modify: `tools/vi-glide-data/README.md`: record the dump date and the exact command used.

**Interfaces:**
- Produces the asset at `ime/dict/vi_glide.tsv.gz`, in the Task 2 format, ≤ 3 MB.

- [ ] **Step 1: Download the dump** `https://dumps.wikimedia.org/viwiki/latest/viwiki-latest-pages-articles.xml.bz2` into a scratch directory outside the repo. Never commit the dump. If the download is impossible in this environment, stop and report BLOCKED with the reason.
- [ ] **Step 2: Run** `python tools/vi-glide-data/build_vi_glide_data.py --input <dump> --output app/src/main/assets/ime/dict/vi_glide.tsv.gz`. Expected: it prints the bytes written (≤ 3,000,000) and the unigram and bigram counts. Raise the thresholds if the result is over budget.
- [ ] **Step 3: Spot-check the output.**
  - `zcat … | head`: the top unigrams include "và", "của", "là", "có" and "được".
  - `zcat … | grep -P '^B\tkhông\tđược\t'` returns a line.
- [ ] **Step 4: Add the attribution line** to the licenses data. Then run `./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL, with the APK size growth ≤ 3 MB compared with `main`.
- [ ] **Step 5: Commit** the asset, the README and the attribution with `git commit -m "feat(android): ship Vietnamese glide statistics derived from Wikipedia"`.

### Task 4: `ViGlideLexicon` (pure model and loader)

**Files:**
- Create: `app/…/vi/glide/ViGlideLexicon.kt`
- Create: `app/…/vi/glide/ViGlideLexiconLoader.kt`: Android assets plus gzip, on `Dispatchers.IO`, memoized.
- Test: `app-test/…/vi/glide/ViGlideLexiconTest.kt`

**Interfaces:**
- Consumes `ViText.fold` and `ViText.withToneStyle` (Task 1), `ViSyllable.isValid` (vi-input), and the Task 2/3 asset format.
- Produces:
  - `data class ViGlideEntry(val word: String, val count: Long, val english: Boolean)`. Vietnamese words are stored canonical MODERN.
  - `class ViGlideLexicon` with:
    - `val foldedForms: List<String>`: the distinct folded forms, Vietnamese plus English.
    - `fun foldedFrequency(folded: String): Double`: 0..1 for the classifier. It is the sum of the form's entries' weighted counts, log-scaled and normalised to the maximum.
    - `fun expansions(folded: String): List<ViGlideEntry>`: sorted by count descending.
    - `fun isKnown(word: String): Boolean`: true for a Vietnamese entry (any tone style) or an English entry.
    - `fun unigramProbability(word: String): Double`
    - `fun bigramCount(prev: String, word: String): Long`
    - `fun bigramTotal(prev: String): Long`
    - `companion fun parse(lines: Sequence<String>, english: Map<String, Int>): ViGlideLexicon`
  - English entries: the top 2,000 of `english` by value, a–z only. Their count is `round(ENGLISH_WEIGHT × (freq / 255) × maxVietnameseCount)` with `ENGLISH_WEIGHT = 0.3`, and their folded form is the word itself.
  - Parsing canonicalizes every Vietnamese syllable and bigram side with `withToneStyle(…, MODERN)`, merges counts that collide, and drops syllables failing `ViSyllable.isValid`.
  - `object ViGlideLexiconLoader { suspend fun get(context: Context): ViGlideLexicon; fun peek(): ViGlideLexicon? }`. `peek` returns null until loaded. The English map is read from `ime/dict/data.json`, as `LatinLanguageProvider` does.

- [ ] **Step 1: Write failing tests**, using an in-memory TSV fixture:
  - `parse groups syllables by folded form`: "được", "đuốc" and "dược" all appear under "duoc", sorted by count.
  - `classic and modern spellings merge`: counts for "hòa" and "hoà" sum into the MODERN "hoà".
  - `invalid syllables are dropped at load`: "xyz", "đđ" and "iphone" are absent.
  - `english top words are folded to themselves with reduced weight`: "ok" is present with `english == true`, and its count is ≤ 0.3 × the maximum Vietnamese count.
  - `english beyond top 2000 is excluded`.
  - `bigram lookups use canonical forms`: `bigramCount("không", "được")` matches the fixture even when the fixture wrote it CLASSIC.
  - `foldedFrequency is within 0..1 and highest for the most frequent form`.
  - `isKnown accepts both tone styles and english`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*ViGlideLexiconTest'`. Expected: FAIL.
- [ ] **Step 3: Implement the lexicon and the loader.** Use compact maps; the target is a few MB of heap.
- [ ] **Step 4: Run the same command.** Expected: PASS.
- [ ] **Step 5: Commit** with `git commit -m "feat(android): Vietnamese glide lexicon with folding and bigrams"`.

### Task 5: Ranking and previous-syllable context

**Files:**
- Create: `app/…/vi/glide/ViGlideRanker.kt`
- Create: `app/…/vi/glide/PreviousSyllable.kt`
- Test: `app-test/…/vi/glide/ViGlideRankerTest.kt`
- Test: `app-test/…/vi/glide/PreviousSyllableTest.kt`

**Interfaces:**
- Consumes `ViGlideLexicon` (Task 4) and `ViText.withToneStyle` (Task 1).
- Produces:
  - `object PreviousSyllable { fun of(textBeforeCursor: CharSequence, isKnown: (String) -> Boolean): String? }`. It returns the lowercased NFC last token, only when it is separated from the cursor by spaces alone and `isKnown(token)` holds. It returns null after punctuation, a newline, a digit, an emoji, or at the start.
  - `object ViGlideRanker`, with constants `const val K = 5`, `const val MAX_RESULTS = 9` and `const val SMOOTHING = 5.0`, and `fun rank(foldedInOrder: List<String>, previous: String?, lexicon: ViGlideLexicon, style: ToneStyle): List<String>`. `foldedInOrder` is the classifier's output, best first; only the first K are used. The scoring formula is in Global Constraints. The output is deduplicated and limited to `MAX_RESULTS`. Vietnamese entries are converted with `withToneStyle(style)`; English entries are left as they are.

- [ ] **Step 1: Write failing tests**, using a fixture lexicon:
  - `khong then duoc gives duoc`: previous "không", folded `["duoc"]` → first result "được".
  - `thuoc then duoc gives duoc with dot`: previous "thuốc" → first result "dược".
  - `no previous uses unigram`: previous null → "được" first, the most frequent.
  - `english word loses to vietnamese syllable after vietnamese context`: previous "có", folded `["the"]` → "thể" before "the".
  - `english only form returns the english word`: folded `["ok"]` → `["ok"]`.
  - `classifier rank decays`: a lower-ranked folded form only wins when its context probability is ≥ 2^r times higher.
  - `tone style applied to output`: CLASSIC with "hoà" in the lexicon → "hòa".
  - `at most 9 results and only first K folded forms considered`.
  - `PreviousSyllable`, with `isKnown` backed by the fixture lexicon:
    - "Không " → "không"; "Không  " → "không";
    - "không, " → null; "2 " → null; "😀 " → null; "" → null; "a\n" → null;
    - "ok " → "ok" (an English entry);
    - `previous syllable ignores non-Vietnamese tokens`: "https://x.vn " → null, and "zzz " → null.
  - `previous syllable is lowercased`: "KHÔNG " → "không".
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*ViGlideRankerTest' --tests '*PreviousSyllableTest'`. Expected: FAIL.
- [ ] **Step 3: Implement both.**
- [ ] **Step 4: Run the same command.** Expected: PASS.
- [ ] **Step 5: Commit** with `git commit -m "feat(android): bigram ranking for Vietnamese glide"`.

### Task 6: Hook into the glide classifier and manager

**Files:**
- Create: `app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/GlideLexicon.kt`
- Modify: `…/ime/text/gestures/StatisticalGlideTypingClassifier.kt`: the word and frequency source go through `GlideLexicon`.
- Modify: `…/ime/text/gestures/GlideTypingManager.kt`: Vietnamese routing, expansion and readiness.
- Modify: `…/ime/text/keyboard/TextKeyboardLayout.kt:116`: glide is also off for `ViInputGate.BLOCKED_VARIATIONS`.
- Create: `app/…/vi/glide/ViGlideRouting.kt`: pure decisions.
- Test: `app-test/…/vi/glide/ViGlideRoutingTest.kt`

**Interfaces:**
- Consumes Tasks 4–5. It also uses `ViInputComposer.isViInput` (or the existing equivalent used by `ViInputGate`), the `ViInputSettings` source, `EditorInstance` text before the cursor, `KeyboardManager.fixCase` and `commitGesture`.
- Produces:
  - `interface GlideLexicon { val words: List<String>; fun frequency(word: String): Double }` (FlorisBoard package). The default implementation wraps `nlpManager.getListOfWords` / `getFrequencyForWord` for the subtype.
  - The classifier gets `fun setLexicon(subtype: Subtype, lexicon: GlideLexicon)` in place of the internal `nlpManager` calls. Pruner caching is keyed by (subtype, lexicon identity).
  - `object ViGlideRouting`:
    - `fun useVietnamese(isViComposer: Boolean): Boolean`
    - `fun glideAllowed(isPassword: Boolean, variation: InputAttributes.Variation): Boolean`
    - `fun candidates(ranked: List<String>, fixCase: (String) -> String): Pair<String?, List<String>>`: returns the commit word and up to 8 smartbar candidates.
  - On the Vietnamese subtype:
    - if `ViGlideLexiconLoader.peek()` is null, start the load and produce **no** suggestions or commit for this glide;
    - otherwise, the classifier works on a `GlideLexicon` adapter over `lexicon.foldedForms` / `foldedFrequency`. On complete, `ViGlideRanker.rank(top folded, PreviousSyllable.of(textBeforeCursor, lexicon::isKnown), lexicon, settings.toneStyle)` runs, and its first result is committed via `commitGesture` and the rest go to `nlpManager.suggestDirectly`. The live preview does the same without committing.
  - The English subtype path is unchanged.

- [ ] **Step 1: Write failing `ViGlideRoutingTest` tests:**
  - `vietnamese only for vi composer`
  - `glide blocked in password email and url`: all `ViInputGate.BLOCKED_VARIATIONS` plus password → false; normal text → true.
  - `candidates split commit and up to 8 suggestions with case applied`
  - `vietnamese lexicon not loaded means no Vietnamese suggestions and no English fallback`: model this as `candidates(emptyList(), …) == (null, emptyList())`, and in the manager, never call the default lexicon when the subtype is Vietnamese.
  - In `ViInputControllerTest`, `finishWord after partial telex then new keys compose fresh` (with `FakeViEditor`): type "dduw", call `finishWord`, then commit " được" as plain text, as a glide would, then type the Telex tone key "s". Neither "đư" nor "được" changes, and the result is "đư được" followed by a literal "s" or a fresh composition, never a tone applied to "được". Skip it if an equivalent test already exists, and name that test in the report. This pins the commit path `commitGesture` → `viInput.finishWord()`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*ViGlideRoutingTest'`. Expected: FAIL.
- [ ] **Step 3: Implement the routing and the hooks.** Mark every FlorisBoard edit `// AIChat MODIFIED`. Ranking runs on `Dispatchers.Default`, as `updateSuggestionsAsync` already does. Never log candidate text.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest` and `./gradlew :app:compileDebugKotlin`. Expected: all PASS, including `ViInputControllerTest`.
- [ ] **Step 5: Commit** with `git commit -m "feat(android): Vietnamese syllable glide through the statistical classifier"`.

### Task 7: Gboard-like defaults

**Files:**
- Modify: `app/src/main/kotlin/dev/patrickgold/florisboard/app/AppPrefs.kt`:
  - the `Glide.enabled` default;
  - the `Gestures.swipeUp`, `swipeDown`, `swipeLeft` and `swipeRight` defaults;
  - the `deleteKeySwipeLeft` default.
- Modify, only if needed: the AIChat settings entry, so the "Glide typing" toggle on `GesturesScreen` is reachable from AIChat's settings home. Verify the existing route first.
- Test: `app-test/…/vi/glide/GestureDefaultsTest.kt`, if `AppPrefs` defaults can be read on the JVM. Otherwise, record in the report that this task's gate is compile plus manual check.

**Interfaces:**
- Produces the default values listed in Global Constraints. No new preference keys, so stored user choices keep winning.

- [ ] **Step 1: Write the failing test**, if feasible: assert the five changed defaults and that `spaceBarSwipeLeft` / `spaceBarSwipeRight` are still `MOVE_CURSOR_LEFT` / `MOVE_CURSOR_RIGHT`.
- [ ] **Step 2: Run it.** Expected: FAIL.
- [ ] **Step 3: Change the defaults.** Add a one-line comment, `// AIChat MODIFIED: Gboard-like defaults (glide owns letter swipes)`.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin`. Expected: PASS.
- [ ] **Step 5: Commit** with `git commit -m "feat(android): Gboard-like glide and gesture defaults"`.

### Task 8: Verification, docs, device check

**Files:**
- Modify: `mobile/android/docs/integration-points.md`: list the new `AIChat MODIFIED` hooks: the classifier lexicon, the manager routing, the layout gate and the defaults.

- [ ] **Step 1: Run** `./gradlew :app:testDebugUnitTest :vi-input:check :app:assembleDebug` and `python -m unittest discover tools/vi-glide-data`. Expected: all green.
- [ ] **Step 2: Update `integration-points.md`.** Then commit with `git commit -m "docs(android): glide typing integration points"`.
- [ ] **Step 3: Device check** with `adb install -r -d app/build/outputs/apk/debug/app-debug.apk`:
  - (a) glide "không được", "cảm ơn bạn", "người ta" and "thuốc dược";
  - (b) switch the tone style setting and glide "hoà" / "hòa";
  - (c) glide "ok" and "check" on the Vietnamese subtype;
  - (d) a password field has no glide trail;
  - (e) tap Telex "dduwowcj" right after a glide gives "được";
  - (f) swipe left on delete removes words with a highlight;
  - (g) a capitalized sentence start;
  - (h) the delay from lift to text feels instant.
  
  Record the results in the report. If no device is attached, mark this step not run.
