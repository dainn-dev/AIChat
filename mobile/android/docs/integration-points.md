# AIChat Keyboard: integration points in the FlorisBoard codebase

Baseline: FlorisBoard `v0.5.2` (see `../UPSTREAM.md`). All paths are relative to `mobile/android/`.
Abbreviation used below: `FB = app/src/main/kotlin/dev/patrickgold/florisboard`.

General rule: put AIChat code in **new** packages or modules, such as `com.aichat.keyboard.*` under
`app/src/main/kotlin/com/aichat/keyboard/` or new Gradle modules. Change upstream files only at thin, marked hook
points (`// AIChat MODIFIED:`), and list each one in `../NOTICE`.

## Architecture primer (what you need to know first)

- **Service:** `FB/FlorisImeService.kt` (`LifecycleInputMethodService`). The UI is Jetpack Compose (`ImeUi()`,
  around line 611). `FlorisImeService.currentInputConnection()` (around line 150) is a static accessor that
  the managers use.
- **Singletons:** `FB/FlorisApplication.kt` creates lazily initialized managers: `keyboardManager`,
  `editorInstance`, `nlpManager`, `subtypeManager`, `clipboardManager`, `themeManager`, `extensionManager`, and
  others. Code gets them with the `Context.xxxManager()` extension functions at the bottom of the same file. New
  AIChat singletons, such as the network client and the auth session, should follow the same pattern or use a DI
  container.
- **Key event flow:** a key press goes from `FB/ime/input/InputEventDispatcher.kt` to
  `FB/ime/keyboard/KeyboardManager.kt`, either `onInputKeyUp(data)` (around line 696) or `handleSpace()`
  (around line 556). From there it reaches `FB/ime/editor/EditorInstance.kt` (`commitChar`, `commitText`,
  `commitCompletion`) and then `FB/ime/editor/AbstractEditorInstance.kt` (`commitChar` with composer handling,
  `commitTextInternal`, and `finalizeComposingText`).
- **Configuration data:** asset-backed "extensions" in `app/src/main/assets/ime/keyboard/*/extension.json`.
  These hold layouts, composers, popup mappings, and subtype presets, and are loaded by
  `FB/lib/ext/ExtensionManager.kt`.
- **Preferences:** `FB/app/AppPrefs.kt` (jetpref datastore; keys such as `smartbar__enabled`,
  `suggestion__enabled`).

## (a) Custom Vietnamese composing engine (`vi-input`)

**What already exists upstream.** FlorisBoard ships a rule-table Telex composer:
`app/src/main/assets/ime/keyboard/org.florisboard.composers/extension.json` contains a `with-rules` composer
with id `telex` and about 2,866 rules. A `vi-VN` subtype preset in
`assets/ime/keyboard/org.florisboard.localization/extension.json` uses it. It is a suffix-rewrite table, so it
cannot handle full Vietnamese tone placement, VNI, or the "undo by repeating a key" rules well. That is why
`vi-input` will replace it.

**Hook points:**

1. **Composer SPI.** `FB/ime/text/composing/Composer.kt`.
   - `interface Composer { id; label; toRead; fun getActions(precedingText, toInsert): Pair<Int, String> }`.
   - Return contract: (number of characters to delete before the cursor, text to insert).
   - Implementations are registered as polymorphic serializers in `FB/lib/ext/ExtensionManager.kt` (around
     lines 72-78, `polymorphic(Composer::class) { subclass(...) }`). They are then referenced from a composers
     `extension.json` with `"$": "<serial-name>"`.
   - **Minimal integration:** add `ViTelexComposer` and `ViVniComposer` classes. Each is `@Serializable` with an
     `@SerialName`, implements `Composer`, and delegates to the `vi-input` engine. Register them in
     `ExtensionManager`, add entries to the composers `extension.json`, and point the `vi-VN` subtype preset's
     `composer` at them.
   - Limitation: `getActions` is stateless and sees only `toRead` preceding characters. Size `toRead` to cover a
     Vietnamese syllable (about 7-8 characters) and make the engine re-derive its state from the preceding text.
     That approach is robust to cursor moves.
2. **Where the composer runs.** `FB/ime/editor/AbstractEditorInstance.kt`, `commitChar(char, ...)` (around
   lines 338-385). It calls `determineComposer(subtypeManager.activeSubtype.composer)`. The method is
   implemented in `FB/ime/editor/EditorInstance.kt` (around line 144) and looks the composer up in
   `keyboardManager.resources.composers`. It then rewrites the tail through
   `setComposingRegion` + `setComposingText`.
   - **If we need a stateful engine** (keeping the composing buffer in our own code instead of re-deriving it),
     intercept in `EditorInstance.commitChar` (around line 199) when the active subtype's composer is a `vi-input`
     one. Feed the key to the engine, and apply the result with `ic.setComposingText(engine.composing, 1)`.
3. **Commit at word boundary.**
   - Space goes through `KeyboardManager.handleSpace()` (around line 556) to `EditorInstance.commitText(" ")`.
   - Punctuation and enter go through `KeyboardManager.onInputKeyUp` to `commitChar` or `performEnter`.
   - Cursor jumps go through `EditorInstance.handleSelectionUpdate` (around line 130).
   - To finalize a composing region, call `AbstractEditorInstance.finalizeComposingText(text)` or
     `ic.finishComposingText()`.
   - Reset the engine in `handleSelectionUpdate`, `handleStartInputView`, and `reset()` (around line 544).
4. **Build wiring (later).** In `settings.gradle.kts`, add `includeBuild("vi-input")`. Then add
   `implementation("<group>:vi-input")` to `app/build.gradle.kts`. The `vi-input` engine must stay pure Kotlin
   and JVM-only, so its unit tests run without Android.

## (b) AI suggestion strip in the smartbar (tap to insert)

- **Smartbar host.** `FB/ime/smartbar/Smartbar.kt`.
  - `Smartbar()` (around line 93) is rendered from `FB/ime/text/TextInputLayout.kt` (around line 65).
  - `SmartbarMainRow` and `CenterContent()` (around lines 209-240) choose between `InlineSuggestionsUi`
    (autofill) and `CandidatesRow()`.
  - `SmartbarSecondaryRow` (around line 370) is a second row.
- **Candidate row.** `FB/ime/smartbar/CandidatesRow.kt`.
  - It collects `nlpManager.activeCandidatesFlow`.
  - Tapping a candidate calls `keyboardManager.commitCandidate(candidate)`.
- **Candidate model.** `FB/ime/nlp/SuggestionCandidate.kt`. This is an interface with `text`,
  `secondaryText`, `confidence`, `icon`, and `sourceProvider`. The existing implementations are
  `WordSuggestionCandidate`, `ClipboardSuggestionCandidate`, and `EmojiSuggestionCandidate`.
- **Commit path.** `KeyboardManager.commitCandidate()` (around line 289) dispatches on the candidate type to
  `EditorInstance.commitCompletion()`, which *replaces the current composing word*. **An AI reply must not
  replace the word.** Add an `AiReplySuggestionCandidate` type plus a branch that calls
  `editorInstance.commitText(reply)`, or `ic.commitText` after optionally clearing the field. A whole reply
  should insert as-is and not be treated as a word completion.
- **Two options:**
  1. **Lowest friction.** Push AI replies with `NlpManager.suggestDirectly(list)` (around line 239). This feeds
     the existing `CandidatesRow`, but local word suggestions will overwrite them on the next keystroke.
  2. **Recommended.** Build a separate `AiSuggestionStrip()` composable, backed by an `AiSuggestionController`
     with a `StateFlow` of state (idle, loading, list of replies, or error), in `com.aichat.keyboard.ui`.
     Render it in `Smartbar.kt` `CenterContent()` instead of `CandidatesRow()` while AI replies are showing, or
     as the `SmartbarSecondaryRow`. Long replies need a horizontally scrolling chip row or a bottom sheet;
     `FB/ime/sheet/BottomSheetHostUi.kt` already exists.
- **"Suggest" button.** Quick actions are `FB/ime/smartbar/quickaction/QuickAction.kt` (`InsertKey`,
  `InsertText`), with the default arrangement in `QuickActionArrangement.kt`. Options:
  - Add a new `KeyCode` constant (see `FB/ime/text/key/KeyCode.kt`) and handle it in
    `KeyboardManager.onInputKeyUp` (around lines 740-760), or
  - Put a dedicated sticky action in `Smartbar.kt` `StickyAction()` (around line 281).
- **Theming.** Smartbar elements are styled through the Snygg theme engine; see the element names in
  `FB/ime/theme/FlorisImeUi.kt`. Add new element names for the AI strip so that themes can style it.

## (c) EN/VI toggle via subtypes and language switching

- **Subtype model.** `FB/ime/core/Subtype.kt`.
  - A subtype has a `primaryLocale`, `composer`, `layoutMap`, `nlpProviders`, and so on.
  - Presets come from the localization `extension.json`: `en-US` uses the `appender` composer, and `vi-VN`
    uses `telex`.
- **Manager.** `FB/ime/core/SubtypeManager.kt` provides:
  - `activeSubtypeFlow` (a `StateFlow`)
  - `switchToPrevSubtype()` (around line 191), `switchToNextSubtype()` (around line 214), and `switchToSubtypeById(id)` (around line 234)
  - `addSubtype()`
  - `shouldShowLanguageSwitch()`
- **Key handling.** In `KeyboardManager`, `KeyCode.LANGUAGE_SWITCH` calls `handleLanguageSwitch()` (around line
  477), and `IME_NEXT_SUBTYPE` / `IME_PREV_SUBTYPE` are handled around line 743. The space-bar swipe and
  long-press behavior is configurable through `SwipeAction` and `UtilityKeyAction`.
- **v1 plan.**
  1. On first run, seed exactly two subtypes, EN (`en-US`, appender) and VI (`vi-VN`, `vi-input` Telex or VNI),
     by calling `subtypeManager.addSubtype(...)` from a setup step. A good place is `FB/app/setup/SetupScreen.kt`
     or an AIChat onboarding screen.
  2. Expose an "EN | VI" toggle by binding a smartbar quick action to `TextKeyData.LANGUAGE_SWITCH`. Upstream
     already adds it to the arrangement in the prefs migration, `AppPrefs.kt` around line 868.
  3. Choose Telex or VNI as a per-subtype composer, which needs no new mechanism, or as one AIChat preference.
  4. Show the language on the space bar. `SpaceBarMode` (`FB/ime/keyboard/SpaceBarMode.kt`) already supports
     showing the current language label.

## (d) AccessibilityService that captures the visible chat on "Suggest"

There is no existing accessibility code upstream. Build it new:

- Add `com.aichat.keyboard.a11y.ChatCaptureAccessibilityService`, declared in `app/src/main/AndroidManifest.xml`
  with `android.permission.BIND_ACCESSIBILITY_SERVICE` and an `<meta-data android:name="android.accessibilityservice"
  android:resource="@xml/aichat_accessibility_service"/>` config.
- Configure it as narrowly as possible: `canRetrieveWindowContent=true`,
  `accessibilityEventTypes=typeWindowContentChanged|typeWindowStateChanged`,
  and `packageNames` limited to the supported messaging apps if possible. Do not use `flagRequestFilterKeyEvents`.
  Google Play's accessibility policy needs a prominent disclosure and an explicit consent screen in the app.
- **Trigger.** When the "Suggest" key is tapped, the IME process calls into the service (same process, so a
  simple in-process singleton or bound interface is enough). The service calls `rootInActiveWindow` /
  `windows`, walks the node tree, and returns the last N message bubbles.
  - Map those bubbles to the backend's `{ role, text }` message list.
  - Use `FlorisEditorInfo.packageName` (`FB/ime/editor/FlorisEditorInfo.kt`, available after
    `EditorInstance.handleStartInputView`, around line 67) to choose a per-app extraction strategy.
- **Privacy guards.**
  - Never capture when `InputAttributes` reports a password, OTP, or no-personalized-learning field. Check
    `activeInfo.inputAttributes` / `imeOptions.flagNoPersonalizedLearning` in `FB/ime/editor/`.
  - Never capture when incognito mode is active (`FB/ime/keyboard/IncognitoMode.kt`).
  - Capture only on an explicit tap, never in the background.
- The IME `onStartInputView` (`FlorisImeService.kt` around line 357) is the place to check whether the
  accessibility service is enabled, and to show a "enable to get suggestions" CTA in the strip if it is not.

## (e) Network/auth core module (Retrofit/OkHttp, encrypted token store, shared session) — DONE (Phase 2)

Implemented in the `:core:network` Android library (`core/network/`, package `com.aichat.keyboard.core.network`)
and the settings screens in `app/src/main/kotlin/com/aichat/keyboard/app/account/`.

- **HTTP stack.** Retrofit 3.0.0 + OkHttp 4.12.0 + the official Retrofit kotlinx-serialization converter
  (kotlinx-serialization is already used across the app). OkHttp gives us `Authenticator` and MockWebServer, which
  the refresh logic and its tests rely on. No logging interceptor, because bodies carry tokens, passwords and chat
  text.
- **API.** `AiChatClient` (all calls return `ApiResult`, nothing throws for HTTP/transport errors):
  `signup`, `login`, `logout`, `me`, `setMemoryOptIn` (`PATCH /me/settings`), `conversations` (paginated) /
  `deleteConversation`, `memories` / `deleteMemory`, and `reply` / `rewrite` / `translate` on `/v1/keyboard/*`
  (with `lang`, `contact_label`, `remember`, `platform`). Wire models in `model/Models.kt` mirror the backend DTOs;
  null optional fields are omitted from request bodies (`explicitNulls = false`).
- **Auth.** Two OkHttp clients share one connection pool: a public one for `/auth/*` and an authenticated one with
  `AuthInterceptor` (Bearer) and `TokenAuthenticator`. The authenticator refreshes once per 401 under a lock
  (single flight: concurrent 401s cause one `/auth/refresh`), persists the rotated refresh token before retrying,
  signs out locally only when `/auth/refresh` answers 401, and keeps the session on any other failure (network,
  5xx, a 400 from a proxy).
  Protected calls fail fast with `Unauthorized` and send nothing while signed out.
- **Errors.** `ErrorEnvelopeMapper` maps `{ error: { code, message, details? } }` to the sealed `ApiError`:
  `Unauthorized`, `QuotaExceeded` (429 `details.limit` / 402 spend cap), `Validation` (400/422, `details.fields`),
  `NotFound`, `Conflict`, `Upstream` (502/503/504), `Network`, `Unknown`.
- **Base URL.** `BuildConfig.AICHAT_API_BASE_URL` of `:core:network`, per build type: debug
  `http://10.0.2.2:3000/` (override with `-Paichat.debugApiBaseUrl=...`), beta/release
  `https://chat-api.dainn.online/` (override with `-Paichat.apiBaseUrl=https://...`; must be https).
- **Token store.** `EncryptedFileTokenStore`: the session JSON is AES-256-GCM encrypted (`AesGcmTokenCipher`,
  random IV, associated data, versioned blob) with a non-exportable Android Keystore key (`AndroidKeystoreKeys`)
  and written atomically to `noBackupFilesDir/aichat/session.bin`. We did not use `androidx.security:security-crypto`
  (deprecated) or Tink (an extra dependency for what is one AES-GCM key and one file). The crypto sits behind
  `TokenCipher`, so the store and the blob format are unit-tested on the JVM with a software key. An undecryptable
  file (key invalidated, tampered) reads as signed out. In direct boot (before first unlock) the store reports
  signed out without caching that answer.
- **Shared session.** `AiChat.client(context)` is a per-process singleton. The IME service and the settings
  activity run in the same process, so they share one client, one token store and one `isSignedIn` flow. Sharing
  with a separate main-app APK is still undecided (see the options below).
  - sign both apps with the same key and expose the session through a signature-protected `ContentProvider`
    or bound service, or
  - have the keyboard run its own login inside its settings activity (what Phase 2 does).
- **Settings UI.** "Tài khoản AIChat" at the top of the settings home: sign in / sign up, account (email, tier,
  today's usage), the "Cho phép AIChat ghi nhớ" toggle with disclosure and a confirmation dialog before enabling
  (disclosure wording approved 2026-11-20) and before disabling (turning it off deletes keyboard-captured
  conversations and the memories learned only from them; the optional `deleted` counts are shown afterwards),
  "Cuộc trò chuyện đã lưu" (paginated, delete) and "Memories" (delete), sign out. Strings are in
  `res/values/aichat_strings.xml` (English) and `res/values-vi/aichat_strings.xml`. These routes have no
  `ui://florisboard` deep link.
- **Still to do (later phases).**
  - IME call sites: use the IME `lifecycleScope`, never `runBlocking`; show loading/error/quota states in the AI
    strip; render the `usage` snapshot from keyboard responses.
  - After "Forget this chat", keep a local per-contact exclusion (`platform` + `contact_label`) and send
    `remember: false` for that contact (the backend keeps no tombstone, see `backend/docs/keyboard-api.md`). The
    settings screen deletes conversations but does not record exclusions yet.
- **Finding 5 (baseline code review) — done:**
  - [x] `res/xml/network_security_config.xml`: `cleartextTrafficPermitted="false"`, system CAs only. The debug
    source set overrides it (`app/src/debug/res/xml/network_security_config.xml`) to allow cleartext only for
    `10.0.2.2` and `localhost`. Wired through `android:networkSecurityConfig`.
  - [x] The exported "Import Extension" intent-filters on `FlorisAppActivity` (`VIEW` with a `content:` URI and
    `SEND`, for `application/vnd.florisboard.extension+zip` and the catch-all `application/octet-stream`) were
    removed, and `FlorisAppActivity.onNewIntent` ignores such intents, since the activity is exported and still
    receives explicit intents. Extensions can still be imported in-app (Addons > Import uses the system picker).
  - [x] The `ui://florisboard` BROWSABLE deep link was removed (manifest intent-filter, NavController deep-link
    registration and the intent-to-navigation handling in `FlorisAppActivity`).

## FlorisBoard features to disable or hide for v1

| Feature | Where | Why |
|---------|-------|-----|
| Addons store / extension import, export, and edit | `FB/app/ext/*`, `AddonBox.kt` (opens `beta.addons.florisboard.org`). The external import intent-filters on `FlorisAppActivity` were already removed in Phase 2 (finding 5). | External content, support burden, upstream branding |
| "Check for updates" of extensions | `FB/app/ext/CheckUpdatesScreen.kt` (opens the addons site) | Points to FlorisBoard infrastructure |
| Glide typing | `FB/ime/text/gestures/GlideTyping*`, pref `glide__enabled` | Not tuned for Vietnamese; experimental upstream |
| Spell-checker service | `FlorisSpellCheckerService` in the manifest, `FB/FlorisSpellCheckerService.kt` | System-wide service outside our scope |
| Clipboard history / clipboard suggestions / `FlorisCopyToClipboardActivity` | `FB/ime/clipboard/*`, prefs `clipboard__*`, `ACTION_SEND image/*` filter in the manifest | Privacy surface; not needed for v1 |
| Media (emoji / emoticon) panel, optional | `FB/ime/media/*` | Keep it if UX wants emoji; it is local-only |
| Theme editor / Snygg stylesheet editing | `FB/app/settings/theme/*` | Power-user feature; lock to AIChat themes |
| Devtools screens and overlay | `FB/app/devtools/*`, `FB/lib/devtools/*` | Debug-only; hide in release |
| Backup and restore | `FB/app/settings/advanced/BackupScreen.kt`, `RestoreScreen.kt` | Android auto-backup and D2D transfer are **already disabled** (`allowBackup="false"`, `res/xml/data_extraction_rules.xml`). The manual backup screen must never export the token store. |
| About / repo / changelog / privacy links to FlorisBoard GitHub | `FB/app/settings/about/AboutScreen.kt`, `FB/app/setup/SetupScreen.kt` (`florisboard__*_url` strings) | Rebrand; keep the open-source licenses screen (Apache-2.0 attribution) |
| Language packs (Han shape-based, etc.), optional | `assets/ime/languagepack/*`, `FB/ime/nlp/han/*` | Trims APK size if only EN/VI ship |
| API 30 inline autofill suggestions | `InlineSuggestionsUi.kt`, pref `suggestion__api30_inline_suggestions_enabled` | Competes with the AI strip for smartbar space |

Remove these from the settings navigation in `FB/app/Routes.kt` and `FB/app/settings/HomeScreen.kt`, and flip
the pref defaults in `AppPrefs.kt`, rather than deleting the code. That keeps upstream merges simple.

## Upstream telemetry and network behavior (audit of v0.5.2)

- **No `INTERNET` permission** in `app/src/main/AndroidManifest.xml` or in any `lib/*` manifest. (AIChat added
  it in Phase 2 for `:core:network`; the upstream code itself still makes no network calls.) No HTTP client
  library is used (no OkHttp, Retrofit, `HttpURLConnection`, or socket usage in `app/` or `lib/`).
- **No analytics, telemetry, or crash-reporting SDK.** `FB/lib/crashutility/CrashUtility.kt` installs a local
  uncaught-exception handler. It stores the stack traces on the device, shows `CrashDialogActivity` and a
  notification, and lets the user *copy* the log to file a GitHub issue by hand. Nothing is uploaded.
  Replace it with our crash reporter (Sentry is already used by the backend) or keep it as a fallback.
- **Only outbound links opened in the browser** through `Context.launchUrl` (`FB/lib/util/`): the addons store
  (`BuildConfig.FLADDONS_STORE_URL = beta.addons.florisboard.org`), the GitHub repo, changelog, privacy policy,
  extension maintainer URLs and `mailto:` links, and a GitHub discussion from `TypingScreen.kt`.
- `FlorisRef` (`FB/lib/io/FlorisRef.kt`) recognizes `http(s)://` refs but only uses them for launching links.
- **Native code.** `lib/native` builds `libfl_native.so` from Rust (`lib/native/src/main/rust`, depends on
  `libnative/dummy`) through CMake and the NDK. Today it is a placeholder (`dummyAdd`). It needs a Rust toolchain
  and the Android Rust targets at build time, and makes no network calls.
- **Build-time network:** Gradle resolves dependencies from Google and Maven Central only (`settings.gradle.kts`). Upstream's `jetpref` snapshot pin had already vanished. We now pin the
  release `0.3.0-rc01` (see `../UPSTREAM.md`). The snapshots repository is now commented out.
