# vi-input

A Vietnamese input engine (Telex and VNI) written in Kotlin for the AIChat Android keyboard.
It is a plain Kotlin/JVM library. It has no Android dependencies, and its tests run on the JVM.

The transformation logic is a Kotlin port of [Bamboo](https://github.com/BambooEngine/bamboo-core)
(MIT), based on commit `b2e49a2`. See [NOTICE](NOTICE).

## Build

```bash
./gradlew test          # unit tests + JaCoCo report (build/reports/jacoco/test/html)
./gradlew check         # also enforces >= 80% line coverage
```

The build needs JDK 17 or newer. Gradle 9.8.2 comes through the wrapper, and the build uses Kotlin 2.4.21 and JUnit 5.14.4.

The library is compiled with Kotlin `languageVersion`/`apiVersion` 2.2 and depends on `kotlin-stdlib` 2.2.20, so the keyboard app (FlorisBoard base, Kotlin 2.2.20) can consume it. The bytecode targets JVM 17, which AGP/D8 handles for minSdk 26. The public API has no inline functions, so app modules that target JVM 11 can call it.

To use it from the Android app, add a composite build:

```kotlin
// mobile/android/settings.gradle.kts
includeBuild("vi-input")

// app or keyboard module
dependencies { implementation("com.aichat:vi-input") }
```

## API

```kotlin
val composer = VietnameseComposer(
    inputMethod = InputMethod.TELEX,      // or InputMethod.VNI
    toneStyle = ToneStyle.MODERN,         // hoà / thuỷ; CLASSIC gives hòa / thủy
    autoRestoreEnglish = true,            // spell-check fallback (see below)
)

var state = ComposerState.EMPTY           // immutable; every call returns a new state
state = composer.process(state, 'n')      // one key
state = composer.process(state, "guowif") // or a whole string
state.composingText                       // "người": show as the composing region
state.rawText                             // "nguowif": the keys as typed
state = composer.backspace(state)         // "ngườ"
composer.commitText(state)                // final text for the word

composer.isWordBreak(' ')                 // true: commit the word here
```

IME loop helper: `type()` processes a key, or commits on a word break:

```kotlin
val outcome = composer.type(state, key)          // key: Char, or the key's text as a String
if (outcome.committed.isNotEmpty()) inputConnection.commitText(outcome.committed, 1)
inputConnection.setComposingText(outcome.state.composingText, 1)
state = outcome.state
```

Notes for the IME integrator:

- **Emoji and other non-BMP text:** pass them as a String, e.g. `type(state, "😀")`. This commits the current word followed by the whole text. `type(state, Char)` and `process` reject a lone surrogate half with `IllegalArgumentException`.
- **Word length cap:** `type()` force-commits a word once it reaches `VietnameseComposer.MAX_WORD_KEYS` (64) keys. Long pasted strings or URLs typed without spaces never grow the composing region without bound.
- **Composing text and committed text can differ.** `composingText` checks a word that is still being typed (prefixes are accepted). `commitText()` checks it as a complete syllable. Examples:
  - `user` shows as `uẻ` while typing and commits as `user`.
  - `tieng` (no hat) shows and commits as `tieng`.
  - `tiengs` shows `tiéng` but commits as `tiengs`.

  So always commit `outcome.committed` / `commitText(state)`. Do not re-commit the last composing text.
- **Virtual horn after backspace:** `huowng` gives hương because the engine adds a horn to the `u` by itself. That horn belongs to the `u`, so after backspacing `ng` the word reads `hươ`, not `huơ`. The raw text (`huow`) is still exact.
- `ComposerState` has value equality on `composingText` and `rawText`. Use it to skip redundant `setComposingText` calls.

To check spelling on its own: `ViSyllable.isValid("người")`, `ViSyllable.isValid("ngh", complete = false)`,
`ViSyllable.split("quốc")` returns `SyllableParts("qu", "ố", "c")`.

`VietnameseComposer` has no mutable state and is safe to share between threads.

## Supported rules

| | Telex | VNI |
|---|---|---|
| sắc huyền hỏi ngã nặng | `s f r x j` | `1 2 3 4 5` |
| remove tone | `z` | `0` |
| â ê ô | `aa ee oo` | `a6 e6 o6` |
| ă ơ ư | `aw ow uw`, a lone `w` gives `ư` | `a8 o7 u7` |
| đ | `dd` | `d9` |

- **Free tone position:** the tone key can come anywhere after the vowel: `hoaf`, `tieesng`, `nguoiwf`.
- **Tone moves when the syllable changes:** `chuyr` gives chuỷ, then `chuyrene` gives chuyển. `hosa` gives hoá.
- **ươ:** `nguowif` gives người. `huowng` gives hương (`uơ` + a final consonant turns into `ươ`). `uwow` gives ươ. `buwoo` gives buô.
- **Tone placement** follows the chosen `ToneStyle` for `oa oe uy` (hoà/hòa, khoẻ/khỏe, thuỷ/thủy). The rest is shared:
  quốc, giữ, gìn, nghiêng, người, chuyển, khuyết. In `iê yê uô ươ` the tone always sits on the marked second vowel, even before the final consonant is typed (`tieens` shows `tiế` and then gives `tiến`).
- **Typing a key twice undoes it:** `ass` gives as, `ddd` gives dd, `aaa` gives aa, `ww` gives w (a third `w` gives `www`). `z`/`0` removes the tone.
- **Case is preserved:** `VIEETJ` gives VIỆT, `DDuowngf` gives Đường.
- **Backspace** removes the last letter and every mark and tone on it. If another vowel should now carry the tone, the tone moves there.
- **Spell-check fallback** (`autoRestoreEnglish = true`):
  - Once a word stops being a valid Vietnamese syllable (or a prefix of one), the remaining keys go in as typed.
  - A word that contains Vietnamese letters but is not valid is shown, and committed, as the raw keys. So `class`, `street`, `awesome`, `window`, `wifi` stay English.
  - Two kinds of word keep the engine's output: words with no Vietnamese letters (`ass` gives as) and words with no vowel (`đc`, `đt`, chat abbreviations).
  - With the flag off, the output is exactly what the Bamboo engine produces.

## Known limitations and differences from Bamboo

- **Input methods:** only Telex and VNI are ported. Telex is Bamboo's "Telex W" (a lone `w` gives `ư`). VIQR, Microsoft layout, the Telex 2 bracket keys (`[` `]`) and the legacy charset encoders (TCVN3, VNI-Win…) are not included.
- **Ambiguous English words:** some English words are also valid Telex and still turn into Vietnamese: `test` gives tét, `was` gives ứa, `wow` gives ươ, `mix` gives mĩ, `box` gives bõ. Bamboo behaves the same way. A dictionary or a per-app toggle would be needed to solve this.
- **One syllable per word:** text with no space between syllables (`khongbiet`) is invalid, so it falls back to the raw keys. Bamboo itself re-splits syllables inside a word.
- **No macros, no "restore last word" toggle, no "tone must follow the vowel" mode.** Free tone marking is always on.
- **Raw text is exact:** `rawText` is always the keys typed. Bamboo drops the escaped key, so its `ww` reports `w`. A third press after a `ww` escape types the key literally (`www`, as in `www.google.com`), while Bamboo gives `wư`.
- **Open iê/yê/uô/ươ:** Bamboo's modern style puts the tone on the first vowel while the syllable is open (`tíê`). Here the tone goes on the marked vowel (`tiế`).
- **Tone repositioning:** Bamboo moves only the last tone transformation, which can leave a replaced tone on the old vowel. Here every tone of the word moves together.
- **Backspace on a toned vowel deletes the tone with it** (`hoà` then backspace gives `ho`, same as Bamboo). The tone only moves when the removed letter did not carry it.
