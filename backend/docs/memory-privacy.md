# Memory Engine — Privacy, Erasure & Cost Controls (MS-6 / DAI-151)

> **Status: PROPOSAL — pending §5.7 owner/compliance sign-off.**
> The engineering controls below are implemented; the retention windows,
> encryption-at-rest posture, and the redaction category list are **defaults
> that compliance must review and sign off** before this workstream closes.

Memories are durable, *derived profiles of third parties* assembled from private
messages — materially more sensitive than Phase-1 screenshots (which store no raw
image). This document records the controls in place and the decisions still owned
by compliance.

## 1. Right to erasure (§5.7, FR-D4) — IMPLEMENTED

- `DELETE /memories/:id` (MS-5) is a **hard delete**. Because the embedding lives
  in the same row, deleting the row also removes its entry from the pgvector
  **HNSW index**, so a deleted memory can never resurface in retrieval (MS-3).
- Verified end-to-end: after delete, the memory is absent from the dashboard
  **and** from the next retrieval, and no row remains in the table.
- There is no soft-delete/tombstone for memories: erasure is real.

### 1.1 Conversation erasure + keyboard capture opt-in (Android Phase 0) — IMPLEMENTED

- **Opt-in:** keyboard (Android AccessibilityService) captures are stored only
  when `users.memory_opt_in = true` (default `false`, set via
  `PATCH /me/settings`), the request names `platform` + `contact_label`, and the
  per-request `remember` flag is not `false`. Turning the setting off stops new
  storage; it does **not** delete what is already stored.
- **Separate keyboard conversations:** `conversations.source`
  (`app | keyboard | share | ocr`) records the creating surface. Keyboard
  captures only ever merge into `source = 'keyboard'` conversations, never into
  a screenshot/share conversation for the same contact.
- **Provenance:** `memory_sources(memory_id, conversation_id)` (unique pair,
  `ON DELETE CASCADE` on both sides) records **every** conversation a memory was
  derived from. The extraction writer links the memory even when the fact
  deduplicates into an existing row. Existing data was backfilled from
  `source_ref` (migration `MemorySources1718900000000`).
- **Discoverability:** `GET /conversations` (owner-scoped, paginated,
  metadata only: id, platform, contact_label, source, message_count,
  updated_at) lists every stored conversation. A capture can be stored even
  when its reply returned `conversation_id: null` (the write outlived the
  ~1.5 s reply budget, or the response was lost), so the list is what makes
  every stored conversation erasable.
- **`DELETE /conversations/:id`** (owner-scoped; another user's id → `404`, no
  IDOR) deletes, in one transaction:
  - the conversation row and its `messages` (`ON DELETE CASCADE`);
  - `screenshots` linked to it (their FK is `SET NULL`, which would otherwise
    leave the OCR text behind);
  - every **extracted** memory that has no source left once this
    conversation's links are removed. A memory still backed by another
    conversation survives, and its `source_ref` is re-pointed to a remaining
    source. A legacy extracted memory with no links at all is matched on
    `source_ref = 'conversation:<id>'`.
  `ai_requests` and `memory_extractions` rows keep only a nulled
  `conversation_id` (audit metadata, no message content).
- **Extraction race / opt-out:** the writer re-checks, inside its write
  transaction, that the source conversation still exists and, for keyboard
  conversations, that the owner is still opted in. It locks the row
  `FOR SHARE`, and erasure locks it `FOR UPDATE`, so the two serialize: either
  the delete runs first and the job drops its results (`aborted`, no audit
  row), or the job commits first and the delete then erases what it wrote via
  `memory_sources`. Extraction also skips opted-out keyboard conversations
  before any LLM call (`opted-out`).

- **Persistent prompt-injection guard (security M3):** a memory with
  `contact_label = NULL` is global and reaches every future prompt. A fact
  that would be stored globally is only auto-accepted when the extractor
  attributes it to "me". Facts attributed to "them" are re-scoped to the
  contact, and anything still global and not from "me" (including unknown
  attribution) is stored `pending_review`, which retrieval never uses until
  the user confirms it. The extraction prompt marks the transcript as
  untrusted data. `contact_label` is sanitized before it is stored (keyboard)
  and before it is interpolated into the extraction prompt (all sources):
  control characters and newlines are stripped, quotes and backticks
  neutralized, and whitespace collapsed. Attribution (`from`) comes from the
  model, so this narrows the injection surface but does not eliminate it.

**Closed in Phase 0 follow-up:** dedupe losing provenance (old gap 1) and the
in-flight extraction race (old gap 3).

**Remaining gaps / decisions:**

1. **Manual memories** (`source = 'manual'`, created on the dashboard) are
   never deleted with a conversation. Manage them via `DELETE /memories/:id`.
2. **Edited extracted memories are still treated as derived.** Editing or
   confirming an extracted memory on the dashboard keeps `source = 'extracted'`,
   so it is erased with its last source conversation. This is the
   privacy-conservative choice. If edits should "adopt" the memory as the
   user's own, `MemoriesService.update` would need to flip `source` to `manual`.
3. **Legacy links:** the backfill links only memories whose `source_ref`
   conversation still existed and belonged to the same user at migration time.
   Additional pre-migration dedupe sources can't be recovered, because
   `source_ref` held only the first one.
4. **Opt-out does not delete stored data, and screenshot/share conversations
   are not gated by `memory_opt_in`.** This is open product question 4.
   Opting out stops new keyboard storage and new extraction of keyboard
   conversations. Retrieval also stops using **memories derived only from
   keyboard conversations**: the pgvector retriever excludes them while
   `memory_opt_in = false`, so they are no longer injected into prompts.
   Memories with any screenshot/share/app source, manual memories, and legacy
   unlinked rows remain retrievable. The excluded rows, and the stored
   keyboard conversations, are kept, still visible on the memory dashboard,
   and become retrievable again if the user opts back in. Erasing them
   requires `DELETE /conversations/:id` / `DELETE /memories/:id`.
5. **Merge history window:** scrolled-up re-captures are recognised only
   within the last 500 stored messages of a conversation (`MERGE_HISTORY_LIMIT`).

## 2. Sensitive-category filter / redaction (§5.7) — IMPLEMENTED (defaults)

Applied at extraction (MS-4), before anything is embedded or stored
(`SensitiveDataFilter`):

- **Dropped entirely** (no profile built): health, financial, and minor-related
  facts.
- **Redacted inline**: email, phone, payment-card-like numbers, street
  addresses → masked as `[redacted-…]`.

Heuristic and intentionally over-broad. **Compliance must confirm the category
list and patterns** (e.g. jurisdiction-specific identifiers, additional special
categories) before sign-off.

## 3. Cost controls (§5.10) — IMPLEMENTED

- **Per-user daily extraction budget** (`MEMORY_EXTRACTION_DAILY_BUDGET`,
  default 200): once reached, new conversations are **shed** (skipped, eligible
  again after the UTC-day reset) rather than running unbounded background spend.
  Unchanged conversations are free (claimed-once; never re-extracted).
- **Batching**: embeddings for a conversation's facts are sent in one batch
  (`embedBatch`); rapid updates to a conversation debounce to a single queued job.

## 4. Retention policy — PROPOSAL (needs sign-off)

Proposed defaults for review:

- Memories persist until the user deletes them or deletes the source
  conversation (FK `ON DELETE CASCADE` already purges memories with their user).
- `pending_review` items not actioned within **N days** (proposed 90) are
  auto-dismissed.
- `superseded`/`dismissed` rows retained **M days** (proposed 30) for audit, then
  purged by a retention sweep (not yet scheduled — depends on the chosen value).

**Decision required:** N, M, and whether a scheduled retention job is in Phase-2
scope or deferred.

## 5. Encryption-at-rest posture — PROPOSAL (needs sign-off / infra)

- Baseline: rely on volume/disk encryption of the managed Postgres instance
  (provider-dependent; not enforced in code here).
- Open question for compliance/infra: whether `memories.content` and/or
  `embedding` require **column-level/application-level encryption** beyond
  at-rest disk encryption, given the third-party-profile sensitivity. This has
  retrieval implications (an encrypted `embedding` cannot be ANN-indexed), so it
  must be decided with the trade-off understood.

## Sign-off checklist (to close DAI-151)

- [ ] Redaction categories/patterns reviewed and approved
- [ ] Retention windows (N, M) and sweep ownership decided
- [ ] Encryption-at-rest posture confirmed (disk-only vs. column-level)
- [ ] Compliance sign-off recorded on DAI-151
