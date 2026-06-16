# Keyboard Backend Contract (P4-1 / DAI-136)

> The GATE for Phase 4. This is the agreed contract the iOS/Android keyboard
> extensions consume. All endpoints are **implemented** (not just mocked) over
> the WS-4 AI pipeline.

## Conventions

- **Base:** `/v1/keyboard` (versioned so the keyboard can pin a contract).
- **Auth:** every endpoint requires `Authorization: Bearer <access_token>`
  (WS-3). Missing/invalid → `401`.
- **Source tagging:** every call is recorded `source: "keyboard"` for analytics
  and quota attribution (clients need not send `source`; the server sets it).
- **Quota:** metered against the **shared per-user daily counter** (the `reply`
  metric; Free = 20/day per DAI-118, Pro unlimited). When exhausted → `429`
  `QUOTA_EXCEEDED`. The slot is reserved→released, so a downstream failure
  refunds it. Every response carries the live `usage` snapshot.
- **Latency:** payloads are intentionally short; keep conversations trimmed.
- **Errors:** common envelope `{ "error": { "code", "message", "details"? } }`
  (400 validation, 401 unauthorized, 429 quota, 502 provider unavailable).

`usage` snapshot shape:
```json
{ "replies_used": 1, "replies_limit": 20, "screenshots_used": 0, "screenshots_limit": 5 }
```

## POST /v1/keyboard/reply

Generate N tone-tagged reply suggestions for a conversation.

Request:
```json
{
  "platform": "whatsapp",
  "conversation": [
    { "sender": "them", "content": "hey, fun weekend?" },
    { "sender": "me", "content": "pretty good! you?" }
  ],
  "tone": "Flirty",
  "count": 3
}
```
- `tone` ∈ `Friendly | Funny | Flirty | Professional | Mature`.
- `count` optional (1–5, default 3). `platform` optional.

Response `200`:
```json
{
  "suggestions": [ { "tone": "Flirty", "text": "…" } ],
  "usage": { "replies_used": 1, "replies_limit": 20, "screenshots_used": 0, "screenshots_limit": 5 }
}
```

## POST /v1/keyboard/rewrite

Rewrite a draft in a tone.

Request:
```json
{ "text": "ok sounds good", "tone": "Professional" }
```
Response `200`:
```json
{ "text": "Sounds good — that works for me.", "usage": { … } }
```

## POST /v1/keyboard/translate

Translate a draft into a target language.

Request:
```json
{ "text": "hẹn gặp lại nhé", "target_lang": "en", "source_lang": "vi" }
```
- `target_lang` required (code or name). `source_lang` optional (auto-detect).

Response `200`:
```json
{ "text": "See you again", "usage": { … } }
```

## Notes for client devs

- Without an `LLM_API_KEY` the server runs the deterministic **stub** provider,
  so the contract is fully exercisable in dev/test (e.g. translate returns
  `"(translated) <text>"`). With a key configured, the same endpoints call the
  real provider — no client change.
- `translate` is a Phase-2 addition to the pipeline (was not previously in any
  workstream); reply/rewrite reuse the existing WS-4 pipeline.
