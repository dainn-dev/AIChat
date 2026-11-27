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
  "platform": "zalo",
  "contact_label": "Linh",
  "lang": "vi",
  "remember": true,
  "conversation": [
    { "sender": "them", "content": "tối nay đi ăn không?" },
    { "sender": "me", "content": "ok luôn" }
  ],
  "tone": "Flirty",
  "count": 3
}
```
- `tone` ∈ `Friendly | Funny | Flirty | Professional | Mature`.
- `count` optional (1–5, default 3). `platform` optional (≤ 64 chars).
- `lang` optional, `en | vi` (Android Phase 0). Suggestions are written in that
  language regardless of the chat's language. **Omitted** → the model replies in
  the conversation's own language. Any other value → `400`.
- `contact_label` optional (trimmed, 1–128 chars; Android Phase 0). The chat
  partner's display name as captured by the keyboard. Scopes memory retrieval
  to that contact and keys the stored conversation.
- `remember` optional boolean (Android Phase 0). Per-request opt-out of
  conversation storage (e.g. incognito chats); only an explicit `false`
  disables it. Must be a JSON boolean (`"false"` → `400`).

Response `200`:
```json
{
  "suggestions": [ { "tone": "Flirty", "text": "…" } ],
  "sticker_tags": ["laugh", "love"],
  "conversation_id": "7b0c4a3e-2f4e-4b8a-9a52-0d6f1f4a9c11",
  "usage": { "replies_used": 1, "replies_limit": 20, "screenshots_used": 0, "screenshots_limit": 5 }
}
```
- `conversation_id` is always present: the stored conversation this capture
  was merged into, or `null`. **`null` does not mean "nothing was stored".** It
  is also returned when the write was slower than the ~1.5 s budget and
  finished in the background, or when the response was lost. Use
  `GET /conversations` to see what is actually stored.
- `sticker_tags` is always present: **0–3** sticker tags describing the
  mood/intent of the suggestions, most fitting first, so the keyboard can offer
  matching stickers. They come from the **same** LLM call as `suggestions` (no
  extra latency or quota). `[]` means "no sticker suggestion" — also returned
  when the model's tags were missing/invalid or the reply output degraded to
  the fallback suggestion. Bad tags never fail the request.
  Tags are only ever from this fixed vocabulary (`STICKER_TAGS` in
  `src/ai/enums/sticker-tag.ts`, kept in sync with Android `StickerTag` wire
  names): `thanks`, `ok`, `laugh`, `love`, `wait`, `great`, `sorry`,
  `goodnight`, `coming`, `yes`, `no`, `congrats`, `eat`, `hello`, `bye`, `sad`,
  `angry`, `surprised`, `cheer`, `busy`, `miss_you`, `thinking`, `cool`,
  `please`. The server lowercases, dedupes and drops anything else. New tags
  may be added later, so clients must ignore tags they don't know. The keyless
  stub provider always returns `["great", "yes"]`.

### Conversation storage (Android Phase 0)

The capture is stored **only when all** of these hold:

1. the user opted in (`PATCH /me/settings { "memory_opt_in": true }`; default
   `false`);
2. the request has both `platform` and `contact_label`;
3. `remember` is not `false`;
4. generation succeeded (a `429`/`502` never stores anything).

Stored captures are merged into one keyboard conversation per
`(user, platform, contact_label)`. It is never merged into a screenshot/share
conversation for the same contact. Messages are matched on `sender` + trimmed
`content`, against the last 500 stored messages:

1. The server appends only what follows the longest overlap between the end
   of the stored history and the start of the window. Re-sending the same
   window stores nothing. This runs first, so a new message is never dropped
   just because the window also occurs earlier in the history.
2. If there is no such overlap, a window of **≥ 3 messages** that already
   appears anywhere in the stored history (the user scrolled up) stores nothing.
3. A window that reaches above the stored start (older messages) but ends
   inside stored history stores nothing, because the older messages can't be
   placed in order.
4. If there is no overlap at all, the whole window is appended.

Known limits: a scrolled-up window whose first message happens to equal the
last stored one (e.g. a lone "ok") is treated as rule 1 and its remainder is
appended; and only the last 500 stored messages are compared
(`MERGE_HISTORY_LIMIT`), so a window scrolled further back than that is
appended again.

Windows of 1–2 messages skip the containment checks, so a genuinely new short
reply such as "ok" is not mistaken for an old one. New messages queue a
background memory extraction.

Storage is **best-effort**: a DB error is logged and swallowed, and the reply
waits at most ~1.5 s for it (`PERSIST_TIMEOUT_MS`), after which it returns
`conversation_id: null` while the write finishes in the background. It never
changes the status code or `suggestions`.

Erase a stored conversation with `DELETE /conversations/:id` (below).

## POST /v1/keyboard/rewrite

Rewrite a draft in a tone.

Request:
```json
{ "text": "ok sounds good", "tone": "Professional", "lang": "en" }
```
- `lang` optional, `en | vi` (Android Phase 0): write the result in that
  language (translating if needed). Omitted → keep the draft's language.

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

## POST /v1/keyboard/write

Writing assistant (the AI panel's "Write for me" chip): the user types a
request ("chúc sinh nhật sếp") and gets a ready-to-send message. Metered like
rewrite. The request is never stored.

Request:
```json
{ "instruction": "chúc sinh nhật sếp", "lang": "vi" }
```
- `instruction` required, 1-2000 chars. `lang` optional, `en` | `vi`
  (omitted -> the instruction's own language). No other fields.

Response `200`:
```json
{ "text": "Chúc sếp sinh nhật vui vẻ, nhiều sức khoẻ và thành công!", "usage": { … } }
```
Unusable model output degrades to the instruction text.

## POST /v1/keyboard/correct

Fix spelling, grammar and punctuation of a draft (the AI panel's "Fix" chip).
Minimal edits: language, tone, wording and slang stay the user's; Vietnamese
typed without diacritics gets them back. Metered like rewrite.

Request:
```json
{ "text": "toi nay ban ranh ko" }
```
- `text` required, 1-2000 chars. No other fields.

Response `200`:
```json
{ "text": "Tối nay bạn rảnh ko?", "usage": { … } }
```
Unusable model output degrades to the original text.

## POST /v1/keyboard/summarize

Summary of the chat on screen (the AI panel's "Summary" chip): what the
conversation is about, how interested the other person seems, and red flags.
One metered generation, like a reply. Read-only: the conversation is **never
stored** by this call, whatever `memory_opt_in` says, so `platform` and
`remember` are not accepted (400).

Request:
```json
{
  "conversation": [{ "sender": "them", "content": "cuối tuần đi cafe ko?" }],
  "lang": "vi",
  "contact_label": "Linh"
}
```
- `conversation` required, 1-100 messages (same shape as `reply`).
- `lang` optional, `en` | `vi`: language of `summary` and red flag messages.
  Omitted → the conversation's own language.
- `contact_label` optional: scopes memory retrieval to this contact only.

Response `200`:
```json
{
  "summary": "Linh rủ bạn đi cà phê cuối tuần và có vẻ hào hứng.",
  "interest_score": 78,
  "red_flags": [{ "code": "pressure", "message": "…" }],
  "degraded": false,
  "usage": { … }
}
```
- `interest_score` is always an integer 0-100.
- `degraded: true` → the model output was unusable; `summary` is an English
  placeholder and clients should show their own "not enough context" text.

## Settings & erasure (Android Phase 0)

These live outside `/v1/keyboard` but gate/undo keyboard storage. Both need
`Authorization: Bearer <access_token>`.

**`GET /me`** now also returns `settings`:
```json
{ "user": { … }, "tier": "free", "usage": { … },
  "settings": { "memory_opt_in": false, "training_opt_in": false } }
```

**`PATCH /me/settings`**
```json
{ "memory_opt_in": true }
```
→ `200 { "settings": { "memory_opt_in": true, "training_opt_in": false } }`.
The body takes `memory_opt_in` and/or `training_opt_in`: at least one is
required, each must be a JSON boolean, a field not sent keeps its value, and
unknown fields → `400`. The response always carries both settings.

`training_opt_in` is the model-training consent (off by default): `true` lets
conversations stored from now on be used to fine-tune the reply model,
`false` leaves the user out of future training and deletes nothing. See
`docs/model-training.md`.

Sending `false` stops future storage **and** hard-deletes, atomically with the
flag change, every keyboard-captured conversation (`source: "keyboard"`), its
messages, and the memories extracted only from keyboard conversations. It
does this on **every** `false` request, even if the setting was already off,
so it is safe to repeat. Screenshot/share conversations and manual memories
are kept. The response reports what was erased:
```json
{ "settings": { "memory_opt_in": false, "training_opt_in": false },
  "deleted": { "conversations": 3, "memories": 5 } }
```
`deleted` is present on every `memory_opt_in: false` response (counts may be `0`) and absent
when sending `true`, which never deletes anything. Turning it back on does not
restore anything, so the client should confirm before sending `false` ("This
will erase chats remembered from the keyboard"). If the erase can't finish in
time, the request fails with `503 ERASURE_TIMEOUT`, nothing has changed (the
setting is still on), and the client should retry. See
`docs/memory-privacy.md` §1.1.

**`GET /conversations?limit=20&offset=0`** → `200`
```json
{
  "conversations": [
    { "id": "…", "platform": "zalo", "contact_label": "Linh", "source": "keyboard",
      "message_count": 12, "updated_at": "2026-11-20T09:15:00.000Z" }
  ],
  "total": 1, "limit": 20, "offset": 0
}
```
Owner-scoped and newest first. It returns metadata only, never message
content. `limit` is 1–100 (default 20) and `offset` ≥ 0; other query params →
`400`. It lists every stored conversation (`source`: `keyboard | ocr | share | app`).
This is how a client finds conversations to erase, including ones whose reply
returned `conversation_id: null`.

**`DELETE /conversations/:id`** → `204` (no body). Hard-deletes the conversation,
its messages, screenshots linked to it, and memories extracted from it.
`404 CONVERSATION_NOT_FOUND` when it does not exist **or belongs to another
user**; `400` when `:id` is not a UUID. See `docs/memory-privacy.md` §1.1.

**Erasing does not stop re-capture.** The backend keeps no tombstone, so the
next `reply` for the same `(platform, contact_label)` from an opted-in user
creates a new conversation. After a user erases a chat ("Forget this chat"),
the **client must keep a per-contact exclusion** (keyed by `platform` +
`contact_label`) and send `remember: false` for that contact from then on,
until the user explicitly re-enables it.

## Notes for client devs

- Without an `LLM_API_KEY` the server runs the deterministic **stub** provider,
  so the contract is fully exercisable in dev/test (e.g. translate returns
  `"(translated) <text>"`). With a key configured, the same endpoints call the
  real provider — no client change.
- The stub ignores `lang` (it returns fixed English text); the language
  instruction only takes effect with a real provider.
- `translate` is a Phase-2 addition to the pipeline (was not previously in any
  workstream); reply/rewrite reuse the existing WS-4 pipeline.
