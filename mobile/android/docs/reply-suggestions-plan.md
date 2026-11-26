# Reply suggestions: plan

Goal: suggest replies to the chat the user is in (Messenger, Zalo, ...), including flirty ones, so replies sound
less dry. The backend already serves `POST /v1/keyboard/reply` (tones Friendly, Funny, Flirty, Professional, Mature;
per-contact memory when `platform` + `contact_label` are sent and the user opted in). See
`backend/docs/keyboard-api.md`.

## Done (foundation, commit on `feat/ios-style-stickers`)

- `com.aichat.keyboard.reply.ConversationCapture.kt`: `CapturedConversation`, `CaptureResult`,
  `ConversationProvider`, `ConversationCapturer` (tries providers in order, never throws, keeps the last 100
  messages).
- `AiAssistController`: reply flow `startReply()` -> `ChoosingReplyTone` -> `requestReplies(tone)` -> capture ->
  `/keyboard/reply` -> `Suggestions(replies)` -> `pickSuggestion()` fills the field (undo restores the draft).
  `Failed(NO_CONVERSATION, captureDisabled)` when no provider found the chat.
- `AiAssist`: providers in order `AccessibilityConversationProvider`, `ClipboardConversationProvider` (both stubs),
  `remember = false` while the keyboard is in incognito mode.
- Smartbar chips: "Gợi ý trả lời" first, tone chips (Tán tỉnh first), suggestion chips, "Tán tỉnh" rewrite chip,
  "Bật" chip that opens the system accessibility settings.

## Workstreams (parallel, one worktree each)

| | Workstream | Owns (create/modify only these) |
|---|---|---|
| A | Accessibility capture: an `AccessibilityService` that reads the visible chat of Messenger (`com.facebook.orca`) and Zalo (`com.zing.zalo`) when asked, a consent/explanation screen in settings, and the real `AccessibilityConversationProvider` | `reply/AccessibilityConversationProvider.kt`, new `com.aichat.keyboard.capture.*`, `res/xml/aichat_accessibility_service.xml`, `AndroidManifest.xml` (service entry only), settings screen + strings with prefix `aichat__capture__` |
| B | Screenshot share: share a chat screenshot to AIChat -> on-device OCR (ML Kit text recognition, bundled model, no Google Play services download) -> split bubbles into me/them by horizontal position -> `/keyboard/reply` -> a sheet with suggestions and a copy button | new `com.aichat.keyboard.share.*`, `app/build.gradle.kts` + `gradle/libs.versions.toml` (ML Kit only), `AndroidManifest.xml` (share activity only), strings with prefix `aichat__share__` |
| C | Keyboard: the real `ClipboardConversationProvider` (recent copy, treated as the partner's message), suggestion chips that fit long replies, and the voice-input (mic) key that does nothing on the test phone | `reply/ClipboardConversationProvider.kt`, `ai/AiSmartbar.kt`, `ui/AiChatImeBottomBar.kt`, voice-related FlorisBoard files, strings with prefix `aichat__ai__` / `aichat__voice__` |

Rules for every workstream: TDD for pure logic (parsers, classifiers, mappers), never read password fields or
send text from them, no user text in logs, mark every edit to a FlorisBoard file with `// AIChat MODIFIED:` and list
it in `NOTICE`, keep `./gradlew :app:assembleDebug :app:testDebugUnitTest` green.

## Risks

- Google Play restricts `AccessibilityService` for non-accessibility use: needs a prominent disclosure, user
  consent, and the Play Console declaration. B is the fallback if it is rejected.
- Messenger/Zalo view trees change between versions; the parser must degrade to `NOTHING_FOUND`, never crash.
- ML Kit adds APK size (bundled Latin model, Vietnamese uses the Latin script).
