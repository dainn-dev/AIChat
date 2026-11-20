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
- **`DELETE /conversations/:id`** (owner-scoped; another user's id → `404`, no
  IDOR) deletes, in one transaction:
  - the conversation row and its `messages` (`ON DELETE CASCADE`);
  - `screenshots` linked to it (their FK is `SET NULL`, which would otherwise
    leave the OCR text behind);
  - `memories` whose provenance is this conversation
    (`source_ref = 'conversation:<id>'`, the format MS-4 writes).
  `ai_requests` and `memory_extractions` rows keep only a nulled
  `conversation_id` (audit metadata, no message content).

**Known gaps (documented, not fixed in Phase 0):**

1. **Deduplicated facts keep the first source.** MS-4 dedupes facts on
   `(user_id, contact_label, kind, content_hash)` with `ON CONFLICT DO NOTHING`,
   so a fact also present in a *later* conversation keeps the `source_ref` of the
   conversation that first produced it. Deleting the later conversation leaves
   that fact (it is still backed by the earlier one); deleting the earlier one
   removes it even if the later one also supports it. Exact per-conversation
   lineage would need a `memory_sources(memory_id, conversation_id)` join table.
2. **Manual/edited memories** (`source = 'manual'`) carry no conversation
   reference and are never deleted with a conversation — they are managed via
   `DELETE /memories/:id`.
3. **In-flight extraction race.** An extraction job that already loaded the
   messages before the delete committed can still write memories afterwards
   (with the deleted conversation's `source_ref`). Jobs that start after the
   delete see `no-conversation` and do nothing. Mitigation if required: have
   the writer re-check the conversation exists inside its transaction.

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
