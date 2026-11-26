# Phase 4 (Keyboard Extension) — Client → Backend Integration Guide

> For the Frontend/Mobile agents building DAI-137–140. The Phase-4 backend gate
> (P4-1 / DAI-136) is implemented and on `main`; this maps each remaining issue
> to the exact endpoints, payloads, and error codes it consumes. No new backend
> work is required for Phase 4.

## Conventions

- **Base URL:** the API host (local dev: `http://localhost:3000`).
- **Auth:** all AI calls require `Authorization: Bearer <access_token>`.
- **Source tagging:** keyboard calls are recorded `source: "keyboard"`
  server-side (analytics + quota). Clients do **not** send `source`.
- **Quota:** metered against the **shared per-user daily reply counter**
  (Free = 20/day; Pro unlimited) — reply, rewrite, and translate all draw on it.
  Over quota → `429` `QUOTA_EXCEEDED`. Every response carries the live `usage`.
- **Error envelope (all endpoints):**
  `{ "error": { "code", "message", "details"? } }` — handle `UNAUTHORIZED` (401),
  validation (400), `QUOTA_EXCEEDED` (429), provider failure `502`.
- **Tones:** `Friendly | Funny | Flirty | Professional | Mature`.
- **Latency:** keep payloads short (trim the conversation) for near-instant UX.

Full endpoint contract: `docs/keyboard-api.md`.

---

## DAI-137 (P4-2) — Shared auth/session plumbing (app ↔ keyboard)

The keyboard reuses the **session the main app holds** (WS-3 auth, DAI-127); no
new backend.

- `POST /auth/login` → `{ access_token, refresh_token, user }`
  - `access_token` (short-lived JWT, ~15m) → iOS **App Group + shared Keychain**,
    Android **shared prefs/Keystore** within the app package; sent as `Bearer`.
  - `refresh_token` (opaque, ~30d).
- `POST /auth/refresh { refresh_token }` → rotated `{ access_token, refresh_token }`
  (old refresh token is revoked — persist the new one).
- `GET /me` → `{ user, tier, usage, settings }` — validate session + seed the
  quota/tier UI. `settings.memory_opt_in` drives the "Remember my chats" toggle;
  `settings.training_opt_in` drives the model-training consent toggle
  (`docs/model-training.md`).
- `PATCH /me/settings { "memory_opt_in": true|false }` → `{ settings, deleted? }`
  (Android Phase 0) — the user-level opt-in for keyboard conversation storage.
  Every `false` request erases all keyboard-captured conversations and
  keyboard-only memories (idempotent) and returns
  `deleted: { conversations, memories }`; `503 ERASURE_TIMEOUT` → retry (see
  `keyboard-api.md`).
- **No valid session:** protected calls return `401` `UNAUTHORIZED` → keyboard
  shows "Sign in via the app" and still types as a plain keyboard. (DoD AC-10.)

## DAI-138 (P4-3) — Android custom IME · DAI-139 (P4-4) — iOS Keyboard Extension

Both consume the same three `/v1/keyboard` endpoints. Source capture is
client-side (clipboard read on explicit user action + manual paste box); an
empty/stale clipboard prompts to copy and makes **no** backend call. Never
auto-send — insert via `commitText` (Android) / `insertText` (iOS) on user tap.
iOS additionally requires **"Allow Full Access"** before any network call.

**Quick Reply** — `POST /v1/keyboard/reply`
```json
{ "platform": "zalo", "contact_label": "Linh", "lang": "vi", "conversation": [{ "sender": "them", "content": "…" }], "tone": "Flirty", "count": 3 }
```
→ `{ "suggestions": [{ "tone": "Flirty", "text": "…" }], "sticker_tags": ["laugh"], "conversation_id": "…" | null, "usage": { … } }`

`sticker_tags`: 0–3 tags from the fixed sticker vocabulary (always present,
`[]` when none; ignore unknown tags). See `keyboard-api.md`.

**Rewrite** — `POST /v1/keyboard/rewrite`
```json
{ "text": "ok sounds good", "tone": "Professional", "lang": "en" }
```
→ `{ "text": "…", "usage": { … } }`

### Android Phase 0 additions (AccessibilityService capture)

- **Language toggle:** send `lang: "en" | "vi"` from the keyboard's EN/VI switch
  on `reply` and `rewrite`. Omit it to let the model answer in the chat's own
  language.
- **Contact:** send `contact_label` (the chat partner's display name read from
  the chat header, ≤ 128 chars) together with `platform` (e.g. `"zalo"`,
  `"messenger"`) on every `reply`. It scopes memory retrieval to that contact.
- **Capture:** send the visible window as `conversation` on every request; don't
  diff client-side. The server merges overlapping windows without duplicates
  and stores them only if the user opted in (`PATCH /me/settings`), both
  `platform` and `contact_label` are present, and `remember` is not `false`.
  Send `remember: false` for chats the user marked private/incognito.
- **`conversation_id`** in the reply is the stored conversation (or `null`).
  `null` does **not** guarantee nothing was stored (a slow write may finish in
  the background), so build "Forget this chat" / privacy screens on
  `GET /conversations` (metadata only, paginated) and
  `DELETE /conversations/:id` → `204` (`404` if missing/not yours).
- **After erasure, keep a local per-contact exclusion** (`platform` +
  `contact_label`) and send `remember: false` for that contact. Otherwise the
  next capture stores the chat again (there is no server-side tombstone).
- Storage is best-effort and never fails the reply. `conversation_id: null` on
  an opted-in request means it was skipped or slow, not that the reply failed.

**Tone Switch** — re-call `reply` (or `rewrite`) with a different `tone`.

**Translate** — `POST /v1/keyboard/translate`
```json
{ "text": "hẹn gặp lại nhé", "target_lang": "en", "source_lang": "vi" }
```
→ `{ "text": "…", "usage": { … } }` (omit `source_lang` for auto-detect; EN↔VI + any target).

## DAI-140 (P4-5) — Cross-cutting: analytics, UX states & QA matrix

- **Analytics:** the backend already emits a PostHog `ai_request` event per
  generation, tagged `source: "keyboard"` with provider/latency/tokens/status
  (via `AiRequestLogger`). The client may add its own UI events; backend-side
  attribution needs no change.
- **UX states — all driven off the responses:**

  | State | Backend signal |
  |---|---|
  | loading + cancel | request in flight (client-side abort) |
  | non-blocking error | `502` (provider) / network error |
  | empty/stale clipboard | none — gated client-side, no call |
  | quota / upgrade | `429` `QUOTA_EXCEEDED` (+ `details.limit`) and `usage.replies_used`/`replies_limit` |
  | signed-out | `401` `UNAUTHORIZED` |

- **QA matrix:** verify clipboard read + text insertion across Instagram,
  Messenger, Telegram, WhatsApp, Zalo on both platforms (client/QA concern; the
  backend contract is identical for all apps).

---

## Summary — Phase-4 backend coverage

| Issue | Backend it calls | Status |
|---|---|---|
| DAI-136 P4-1 contract | `/v1/keyboard/{reply,rewrite,translate}` | ✅ on `main` (Done) |
| DAI-137 session bridge | `/auth/login`, `/auth/refresh`, `/me` (WS-3) | ✅ on `main` |
| DAI-138 / 139 IME + ext | the three `/v1/keyboard` endpoints | ✅ on `main` |
| DAI-140 analytics/UX/QA | PostHog `ai_request` (server-side) + error/`usage` contract | ✅ on `main` |
| Android Phase 0 | `lang`, `contact_label`, `remember`, `conversation_id`; `PATCH /me/settings`; `GET /conversations`; `DELETE /conversations/:id` | branch `feat/backend-phase0` |
| Android stickers | `sticker_tags` on `/v1/keyboard/reply` | branch `feat/stickers-backend` |

All Phase-4 endpoints are exercisable keyless (stub LLM provider) for client
dev/test. Related contracts: `docs/keyboard-api.md`, `docs/share-api.md`,
`docs/phase3-integration.md`.
