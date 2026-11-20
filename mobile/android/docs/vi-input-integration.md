# vi-input integration (Vietnamese typing, EN ⟷ VI)

Phase 1 of the Android keyboard plan. The keyboard types Vietnamese with our own engine
(`vi-input/`, `com.aichat:vi-input`, package `com.aichat.vi`) instead of FlorisBoard's Telex rule table,
and the globe / language key switches between English and Tiếng Việt.

Paths are relative to `mobile/android/`. `FB = app/src/main/kotlin/dev/patrickgold/florisboard`,
`VI = app/src/main/kotlin/com/aichat/keyboard/vi`.

## Build wiring

- `settings.gradle.kts`: `includeBuild("vi-input")` (Gradle composite build). The app depends on
  `implementation("com.aichat:vi-input")`, which Gradle substitutes with the included project.
- vi-input keeps its own build (Kotlin plugin 2.4.21, Gradle 9.8.2 wrapper). Inside the composite build it
  runs on the host's Gradle 9.2.0 with its own plugin classpath, so the app's Kotlin 2.2.20 is not affected.
  It emits Kotlin metadata 2.2, depends on `kotlin-stdlib` 2.2.20 and targets JVM 17 bytecode, which D8
  desugars for minSdk 26. The app compiles with `jvmTarget` 11; that is fine because the vi-input public API
  has no inline functions.
- vi-input's tests still run standalone (`cd vi-input && ./gradlew test`) and from the host
  (`./gradlew :vi-input:check`).

## Design: stateful adapter behind FlorisBoard's composer hook

FlorisBoard's `Composer` interface (`FB/ime/text/composing/Composer.kt`) is stateless: for each key it gets
the preceding text and returns "delete N chars, insert this". vi-input is a reducer over an immutable
`ComposerState` that keeps the raw keys (`user` is shown as `uẻ` while typing and committed as `user`), so the
state cannot be re-derived from the text on screen. We therefore keep the state ourselves, but still let
FlorisBoard apply the edits.

Options considered:

1. **Pure `Composer` implementation, re-deriving state from the preceding text** (what
   `integration-points.md` called "minimal"). Rejected: the raw keys are lost, so auto-restore of English
   words, `ww`/`ddd` escapes and VNI digits after a word cannot work, and backspace never goes through the
   engine.
2. **Full intercept in `EditorInstance`/`KeyboardManager` that talks to the `InputConnection` directly**
   (`setComposingText` on a composing region we own). Rejected: FlorisBoard owns the composing region (it
   sets it to the current word for suggestions, or finishes it when suggestions are off) and tracks expected
   editor content in a queue. A second owner would fight it, and a finished region turns the next
   `setComposingText` into a duplicate insert.
3. **Chosen: stateful adapter + thin hooks.** `ViInputController` (`VI/ViInputController.kt`, no Android
   dependencies) owns the `ComposerState`. Its `composerAdapter` implements FlorisBoard's `Composer`, so letters
   still flow through `AbstractEditorInstance.commitChar`, which replaces "the last N chars" with
   `setComposingRegion` + `setComposingText` and keeps FlorisBoard's expected-content tracking, auto-space and
   phantom-space logic intact. This is the same mechanism the upstream Telex table used, so it is proven
   against real editors. Backspace and word finishing use the same replace operation, exposed as
   `AbstractEditorInstance.replaceTextBeforeCursor` (extracted unchanged from `commitChar`).

How it fits together:

- The composers extension (`assets/ime/keyboard/org.florisboard.composers/extension.json`) lists a marker
  composer `{ "$": "aichat-vi-input" }` (`VI/ViInputComposer.kt`, id `vi-input`), registered in
  `ExtensionManager`. `EditorInstance.determineComposer` asks `ViInputBridge.resolveComposer` to swap the
  marker for the stateful adapter.
- The controller only ever rewrites the last `state.composingText.length` chars before the cursor. Before each
  edit it checks that the text before the cursor still ends with the word; if not (the app or the user
  changed the text), it drops the word and starts fresh instead of corrupting text.
- `VI/ViInputBridge.kt` is the Android glue: it reads the settings, decides whether vi-input is active
  (vi-input subtype, letters layer shown, not a password field), implements `ViEditorPort` on top of `EditorInstance`, and
  finishes the word when the user leaves the Vietnamese subtype.

Event mapping (each hook is one line in `FB/ime/editor/EditorInstance.kt`, marked `AIChat MODIFIED`):

| Event | Path | What happens |
|-------|------|--------------|
| Letter / VNI digit / punctuation | `commitChar` → adapter `getActions` | `composer.type(state, key)`; replace the old word with `committed + newComposing`. Word breaks (punctuation, Telex digits) commit via `commitText(state)` inside `type`. |
| Emoji / non-BMP key | `commitChar` (single grapheme) → `type(state, CharSequence)` | Commits the word, then the emoji, unchanged. Multi-code-point keys finish the word first. |
| Space, enter, paste, media emoji | `commitText` | `finishWord`: replace the shown word with `composer.commitText(state)` (e.g. `uẻ` → `user`), reset, then FlorisBoard commits the text. |
| Send / Go / Search action | `performEnterAction` | `finishWord` first, so the app receives the final text. |
| Backspace | `deleteBackwards` | While composing: `composer.backspace(state)` and replace the word. Otherwise the normal delete. |
| Cursor move / selection | `handleSelectionUpdate` | Reset, unless the update is ours. LatinIME's rule: a user move starts at the cursor position we expect and ends elsewhere; belated updates from fast typing are ignored. |
| Keyboard moves the cursor / selects | `setSelection`, `setSelectionSurrounding`, `performClipboardSelectAll` | `finishWord` first. |
| Undo / redo | `performUndo`, `performRedo` | Reset (the editor rewrites the text itself). |
| Suggestion tapped | `commitCompletion` | Reset (the candidate replaces the word). |
| New field / input finished / restart | `handleStartInputView`, `reset()` | Reset. |
| Subtype switched away from VI | `activeSubtypeFlow` collector in `ViInputBridge` | `finishWord`. |
| Settings changed | read on every key | The next key uses the new composer; a half-typed word is dropped from the state (its text stays). |

Not routed through the engine (same as upstream Telex): raw editors (`TYPE_NULL`, e.g. terminals), editors that
do not report text/selection to FlorisBoard, and text typed while a selection is active. Password fields use
plain input. Keys from the symbols / numeric layers are typed as is and end the word, so in VNI the tone
digits come from the number row (Settings > Keyboard > Number row) or the long-press digit hints on the letter
keys.

## Subtypes (EN ⟷ VI)

- The default of the `localization__subtypes` preference (`FB/app/AppPrefs.kt`) is now
  `VI/AiChatDefaultSubtypes.kt`: English (`en-US`, QWERTY, `appender`) and Tiếng Việt (`vi-VN`, QWERTY,
  `org.florisboard.composers:vi-input`, `vietnamese_dong`, `vi-VN` popups), with fixed ids 1 and 2.
- The upstream Telex table is removed from the composers extension and the `vi-VN` preset points at `vi-input`,
  so a Vietnamese subtype added in settings also uses our engine. There is one Vietnamese path.
- With two subtypes, `KeyboardManager.shouldShowLanguageSwitch()` is true and the utility key (default
  `DYNAMIC_SWITCH_LANGUAGE_EMOJIS`) shows the language key, which calls `SubtypeManager.switchToNextSubtype()`.
  With two subtypes that is a toggle.
- The last choice is remembered: `SubtypeManager` writes `localization__active_subtype_id` on every switch and
  restores it on start (`evaluateActiveSubtype`). The first run starts in English (the default id `-1`
  matches neither subtype, so the first one is chosen).

## Settings

Keyboard settings (`FB/app/settings/keyboard/KeyboardScreen.kt`) start with a "Tiếng Việt" group
(`VI/ViInputPreferences.kt`, strings in `res/values/aichat_vi_strings.xml`):

| Setting | Preference key | Default |
|---------|----------------|---------|
| Kiểu gõ: Telex / VNI | `aichat_vi__input_method` | Telex |
| Kiểu đặt dấu: Mới (hoà, thuỷ) / Cũ (hòa, thủy) | `aichat_vi__tone_style` | Mới |
| Tự khôi phục từ tiếng Anh | `aichat_vi__auto_restore_english` | on |

The preferences live in `FlorisPreferenceModel.aichatVietnamese` (jetpref). The bridge reads them on every
key, so a change applies to the next word without restarting the keyboard.

## Tests

- `app/src/test/kotlin/com/aichat/keyboard/vi/ViInputControllerTest.kt`: key sequences against a fake editor
  (`FakeViEditor`, which records replace/commit/delete calls like a fake `InputConnection`) driven the way
  `EditorInstance` drives the controller (`ViTestKeyboard`).
- `ViInputConfigTest.kt`: default subtypes JSON, and the real composers / localization `extension.json` assets
  parse to `vi-input` with no Telex table.

Run: `./gradlew :app:testDebugUnitTest :vi-input:check`.
