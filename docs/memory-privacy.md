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
