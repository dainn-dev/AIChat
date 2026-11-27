# AI Assistant Off by Default — Short Design

**Date:** 2026-11-27 · **Surface:** Android keyboard (`mobile/android`) · **Status:** approved by the user (2026-11-27)

## Intent
The AI chat features should be opt-in. By default the keyboard behaves as a plain keyboard. Users turn the AI on in
Settings.

## Decided behavior
- New preference `prefs.aichatAi.enabled` (key `aichat_ai__enabled`), **default false**. Existing users get it off too,
  because this is a default with no stored value.
- **Off:**
  - The AI button is not rendered in the bottom bar (`AiChatImeBottomBar`). The clipboard key moves into its place.
  - If the AI panel is open when the setting turns off, it closes (`AiAssistController.reset()`).
  - The "Đọc tin nhắn để gợi ý trả lời" (chat reader) entry is hidden from the settings home.
- **On:** everything behaves exactly as today.
- **Toggle:** a "Trợ lý AI" switch at the top of the settings home, directly below the AIChat account entry. It
  follows the existing `aichatStickerSuggest` switch pattern. Strings go in en and vi.

## Approaches
- Chosen: a single preference gate in the UI (button, panel close, settings entry).
- Rejected: a build-time flag. Users could not turn the AI on themselves.
- Rejected: hiding only the icon. The chat reader entry would then point to a feature nobody can reach.

## Non-goals
Gating the Share-menu AI reply target. The user chooses it explicitly, so it stays available while the switch is
off (user's decision, 2026-11-27). Changing the AI features themselves, the backend, or the chat-reader accessibility
service. A service the user already
enabled stays enabled; only the settings entry is hidden.

## Tests
- A pure `AiFeatureGate` holds the visibility decisions and gets JVM unit tests.
- The rest is compile plus a device check: default off hides the button, turning it on shows the button and the chat
  reader entry, and turning it off while the panel is open closes the panel.
