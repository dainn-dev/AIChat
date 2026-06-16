# Share-Menu Backend Contract (P3 / DAI-122)

> The backend gate for Phase 3 (Share Menu Extension), mirroring the Phase-4
> keyboard contract (P4-1). One round-trip: a screenshot shared from another app
> is OCR'd on-device (Decision #2), submitted here, persisted, and analyzed.

## Conventions

- **Base:** `/v1/share` (versioned so the share extension can pin a contract).
- **Auth:** requires `Authorization: Bearer <access_token>`. Missing/invalid → `401`.
- **Source tagging:** the analysis is recorded `source: "share"` for analytics
  and quota attribution (clients need not send it; the server sets it).
- **Quota:** metered against the **shared per-user daily screenshot counter**
  (Free = 5/day, per DAI-118 / WS-6) via reserve→release — the same counter as
  `POST /screenshots`. Sharing is a screenshot operation; the analysis step is
  **not** separately metered. Over quota → `429` `QUOTA_EXCEEDED`.
- **No raw image stored** (retention policy): the client sends OCR text +
  extracted messages; `s3_key` stays NULL.
- **Errors:** common envelope `{ "error": { "code", "message", "details"? } }`
  (400 validation, 401 unauthorized, 422 `OCR_FAILED` on blank/garbled input,
  429 quota, 502 provider unavailable).

## POST /v1/share/analyze

Persist a shared conversation and return its analysis in one call.

Request (identical to `POST /screenshots`):
```json
{
  "ocr_text": "them: hey, fun weekend?\nme: pretty good! you?",
  "extracted_messages": [
    { "sender": "them", "content": "hey, fun weekend?" },
    { "sender": "me", "content": "pretty good! you?" }
  ],
  "platform": "instagram",
  "contact_label": "Alex",
  "conversation_id": "<optional — append to an existing conversation>"
}
```

Response `200`:
```json
{
  "screenshot_id": "…",
  "conversation_id": "…",
  "summary": "…",
  "interest_score": 62,
  "suggested_replies": [ { "tone": "Friendly", "text": "…" } ],
  "red_flags": [],
  "usage": { "replies_used": 0, "replies_limit": 20, "screenshots_used": 1, "screenshots_limit": 5 }
}
```

- Blank/garbled OCR (no usable text/messages) → `422 OCR_FAILED`, and the
  screenshot counter is **not** advanced (rejected before quota is reserved).

## Notes for client devs

- Keyless dev/test runs use the deterministic stub LLM provider, so the contract
  is fully exercisable without an `LLM_API_KEY`; with a key the same endpoint
  calls the real provider — no client change.
- This composes the existing WS-5 ingest + analyze; persistence, quota, and the
  retention policy are shared with `POST /screenshots`.
