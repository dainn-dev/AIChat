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
- `GET /me` → `{ user, usage }` — validate session + seed the quota/tier UI.
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
{ "platform": "whatsapp", "conversation": [{ "sender": "them", "content": "…" }], "tone": "Flirty", "count": 3 }
```
→ `{ "suggestions": [{ "tone": "Flirty", "text": "…" }], "usage": { … } }`

**Rewrite** — `POST /v1/keyboard/rewrite`
```json
{ "text": "ok sounds good", "tone": "Professional" }
```
→ `{ "text": "…", "usage": { … } }`

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

All Phase-4 endpoints are exercisable keyless (stub LLM provider) for client
dev/test. Related contracts: `docs/keyboard-api.md`, `docs/share-api.md`,
`docs/phase3-integration.md`.
