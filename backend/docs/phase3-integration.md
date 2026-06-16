# Phase 3 (Share Menu) — Client → Backend Integration Guide

> For the Frontend/Mobile agents building DAI-131–135. Every backend dependency
> for Phase 3 is already implemented and on `main`; this maps each issue to the
> exact endpoints, payloads, and error codes it consumes. No new backend work is
> required for Phase 3.

## Conventions

- **Base URL:** the API host (local dev: `http://localhost:3000`).
- **Auth:** all Phase-3 calls require `Authorization: Bearer <access_token>`.
- **Error envelope (all endpoints):**
  ```json
  { "error": { "code": "STRING_CODE", "message": "…", "details": { } } }
  ```
  Codes Phase-3 clients must handle: `UNAUTHORIZED` (401), validation (400),
  `OCR_FAILED` (422), `QUOTA_EXCEEDED` (429), provider failure `502`.
- **Usage snapshot** (returned by `/me`, `/v1/share/analyze`, `/v1/keyboard/*`):
  ```json
  { "replies_used": 0, "replies_limit": 20, "screenshots_used": 1, "screenshots_limit": 5 }
  ```
  `*_limit` is `null` for Pro (unlimited). Drives the quota/upgrade UX (DAI-135).

---

## DAI-131 (P3-WS-A) — Shared session bridge

The share extension reuses the **session the main app already holds** (WS-3 auth,
DAI-127). No new backend; the extension reads/refreshes the app-issued tokens.

- `POST /auth/login` → `{ access_token, refresh_token, user }`
  - `access_token`: short-lived JWT (default 15m) → store in the App Group
    (iOS) / shared prefs+Keystore (Android); the extension sends it as
    `Bearer`.
  - `refresh_token`: opaque, long-lived (default 30d).
- `POST /auth/refresh { refresh_token }` → new `{ access_token, refresh_token }`
  (refresh token **rotates** — persist the new one; the old one is revoked).
- `GET /me` → `{ user, usage }` — use to validate the session and seed quota UI.
- **No valid session:** any protected call returns `401` `UNAUTHORIZED` → show
  the "Sign in via the app" state; the extension still works as a plain share
  target (no AI).

## DAI-132 (P3-WS-B) — Android Intent Share target · DAI-133 (P3-WS-C) — iOS Share Extension

Native receivers (Android `Intent`/`image/*`, iOS `NSExtensionActivationRule`).
On-device OCR (Decision #2) produces `{ ocr_text, extracted_messages[] }`. No
backend call until the user triggers analysis (see DAI-134). Empty/garbled OCR
should be caught client-side, but the backend also rejects it (`422 OCR_FAILED`).

## DAI-134 (P3-WS-D) — Client analyze flow + result UI

Single round-trip — **`POST /v1/share/analyze`** (contract: `docs/share-api.md`).

Request:
```json
{
  "ocr_text": "them: hey…\nme: …",
  "extracted_messages": [{ "sender": "them", "content": "…" }, { "sender": "me", "content": "…" }],
  "platform": "instagram",
  "contact_label": "Alex"
}
```
Response `200`:
```json
{
  "screenshot_id": "…", "conversation_id": "…",
  "summary": "…", "interest_score": 62,
  "suggested_replies": [{ "tone": "Friendly", "text": "…" }],
  "red_flags": [],
  "usage": { … }
}
```
- Render `summary` + `interest_score` (0–100) + `red_flags`.
- `suggested_replies` come back in the **same response** — no separate reply
  call is needed for the share surface.
- Tagged `source: "share"` server-side (analytics/quota). The client does **not**
  send `source`.
- Append to an existing thread by passing `conversation_id` from a prior call.

## DAI-135 (P3-WS-E) — Quota / error UX

Drive entirely off the responses above:

| Situation | Backend signal | UX |
|---|---|---|
| Over daily limit | `429` `QUOTA_EXCEEDED` (`details.metric`, `details.limit`) | paywall / upgrade prompt |
| Near/at limit | `usage.screenshots_used` vs `screenshots_limit` | counter, soft warning |
| Blank/garbled OCR | `422` `OCR_FAILED` | "couldn't read this image" (no quota spent) |
| Signed out | `401` `UNAUTHORIZED` | "Sign in via the app" |
| Provider down | `502` | non-blocking "try again" |

- The screenshot quota is **shared** with in-app uploads (Free = 5/day) — a
  shared analyze and an in-app screenshot both decrement the same counter.

---

## Summary — Phase-3 backend coverage

| Issue | Backend it calls | Status |
|---|---|---|
| DAI-131 session bridge | `/auth/login`, `/auth/refresh`, `/me` (WS-3) | ✅ on `main` |
| DAI-132 / 133 share targets | none until analyze | n/a |
| DAI-134 analyze flow | `POST /v1/share/analyze` | ✅ on `main` |
| DAI-135 quota/error UX | error codes + `usage` snapshot | ✅ on `main` |

All Phase-3 endpoints are exercisable keyless (stub LLM/embedding providers) for
client dev/test. Related contracts: `docs/share-api.md`, `docs/keyboard-api.md`.
