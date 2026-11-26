# Contact-Specific Conversation Style Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve per-contact keyboard reply suggestions by retaining labeled two-sided conversation history and deriving voice guidance only from the user's own messages.

**Architecture:** Keep the existing conversation capture, `me`/`them` labels, memory opt-in, retention, and erasure pathways. Add a contact-scoped, provenance-linked style memory extracted from `me` turns only, retrieve it for reply generation, and make speaker perspective explicit in the prompt. Use the existing memory extraction/writer system where it can preserve the required `me`-only provenance and deletion behavior; avoid a parallel storage model unless inspection proves it cannot meet those invariants.

**Tech Stack:** NestJS 10, TypeScript, TypeORM, PostgreSQL/pgvector, Jest.

**Spec:** `backend/docs/superpowers/specs/2026-11-26-contact-conversation-style-design.md`

## Global Constraints

- Persist keyboard captures only with memory opt-in, identified platform/contact, and no explicit `remember: false`.
- Preserve explicit `me` / `them` message roles; derive user voice guidance only from `me` messages.
- Use `them` messages as conversation context, never as the user's style source.
- Preserve owner-scoped deletion, opt-out erasure, message retention, and sensitive-data filtering/provenance.
- Do not include conversation content in logs or metadata listings.
- Initial scope is Android keyboard capture; other surfaces remain out of scope.

## Review Focus

- A conversation containing only `them` messages must not create user-style guidance; pin in Task 1 extraction tests.
- A `me`/`them` mixed conversation must extract style from `me` only and preserve labels in recent context; pin in Tasks 1 and 2.
- A label containing prompt-like text must remain sanitized/untrusted; pin in Task 2 prompt tests.
- Opt-out or conversation deletion during/after extraction must leave no usable style derived solely from erased keyboard data; pin in Task 1 provenance/erasure tests and Task 3 integration test.
- Missing contact identity, empty/oversized history, or disabled extraction must preserve current best-effort behavior; pin in Task 1 and Task 3 tests.

---

## File Structure

- Modify `backend/src/memory/extraction/extraction-provider.interface.ts` and its provider implementation/specs only as needed to represent a distinct per-contact style result sourced strictly from `me` messages.
- Modify `backend/src/memory/memory-extraction.service.ts` and its spec to construct an explicitly labeled `me`-only style input while retaining bounded history, consent checks, sensitive filtering, and conversation provenance.
- Modify `backend/src/ai/pipeline/ai-pipeline.service.ts` and `backend/src/ai/pipeline/context-builder.ts` (+ specs) to pass contact-specific style guidance and explicit speaker-role instructions to reply generation.
- Modify `backend/src/ai/memory/pgvector-memory-retriever.ts` (+ specs) only if style memories need a narrowly scoped retrieval constraint; preserve existing owner/contact/opt-out filters.
- Add or update migrations only if an existing memory representation cannot express the required style kind/provenance safely; use a reversible migration and database tests.
- Update `backend/docs/keyboard-api.md` or memory privacy docs only if the client contract or privacy behavior changes; otherwise no API contract changes are expected.

## Task 1: Derive contact style from the user's messages with provenance

**Files:**
- Modify: `backend/src/memory/extraction/extraction-provider.interface.ts`
- Modify: `backend/src/memory/extraction/openai-extraction.provider.ts`
- Modify: `backend/src/memory/memory-extraction.service.ts`
- Test: `backend/src/memory/extraction/openai-extraction.provider.spec.ts`
- Test: `backend/src/memory/memory-extraction.service.spec.ts`
- Possibly modify: `backend/src/memory/memory-writer.service.ts` and associated specs if an existing kind/provenance path cannot represent a contact style profile.

**Interfaces:**
- Consumes: ordered `{ sender: string; content: string }[]` conversation messages and existing `contactLabel`.
- Produces: a style fact/profile stored as a contact-scoped extracted memory with `sourceRef: conversation:<id>` and conversation link; only `sender === 'me'` content may determine style.

- [ ] **Step 1: Write failing provider tests** asserting mixed `me`/`them` history gives the extractor role-labeled input and instructions that style is derived only from `me`; `them`-only history yields no user-style fact.
- [ ] **Step 2: Run** `cd backend && npm test -- --runInBand openai-extraction.provider` and confirm the new assertions fail for the expected missing behavior.
- [ ] **Step 3: Implement** the smallest provider/interface change that expresses a contact-specific communication-style fact while retaining ordinary fact extraction behavior.
- [ ] **Step 4: Write failing orchestration tests** asserting contactless conversations cannot write a contact style profile, style output passes through `SensitiveDataFilter`, provenance links to the conversation, and opted-out/erased captures are not sent/written.
- [ ] **Step 5: Run** `cd backend && npm test -- --runInBand memory-extraction.service` and confirm the new assertions fail.
- [ ] **Step 6: Implement** role-aware extraction in `MemoryExtractionService`; retain the existing bounded extraction window, daily budget and consent gates. Reuse `MemoryWriterService` and its provenance links when possible; add schema changes only if the current memory kinds/storage cannot safely distinguish style guidance.
- [ ] **Step 7: Run** `cd backend && npm test -- --runInBand memory-extraction.service openai-extraction.provider memory-writer.service` and confirm PASS.

## Task 2: Retrieve voice guidance and enforce speaker perspective

**Files:**
- Modify: `backend/src/ai/pipeline/ai-pipeline.service.ts`
- Modify: `backend/src/ai/pipeline/context-builder.ts`
- Test: `backend/src/ai/pipeline/ai-pipeline.service.spec.ts`
- Test: `backend/src/ai/pipeline/context-builder.spec.ts`
- Possibly modify: `backend/src/ai/memory/pgvector-memory-retriever.ts` and `backend/src/ai/memory/pgvector-memory-retriever.spec.ts` if style memories require a distinct retrieval path.

**Interfaces:**
- Consumes: existing normalized `NormalizedMessage[]`, contact-scoped `RetrievedMemory[]`, tone, count and optional output language.
- Produces: reply prompt with separately labeled latest conversation roles and explicitly labeled style reference derived from `me`; system instruction must preserve the speaker as the user and avoid assigning `me`'s perspective/pronouns to `them`.

- [ ] **Step 1: Write failing context-builder tests** asserting the prompt identifies `me` as the reply author, `them` as the recipient, displays conversation speaker labels, treats style memory as untrusted reference data, and preserves existing JSON/language instructions.
- [ ] **Step 2: Run** `cd backend && npm test -- --runInBand context-builder` and confirm new assertions fail.
- [ ] **Step 3: Implement** the explicit role and contact-style prompt sections in `ContextBuilder`, retaining prompt-injection safeguards and avoiding interpolating raw contact labels as instructions.
- [ ] **Step 4: Write failing pipeline tests** asserting retrieved contact-scoped style context reaches `buildReplyPrompt`, while missing contact or retrieval failure continues to produce replies without style context.
- [ ] **Step 5: Run** `cd backend && npm test -- --runInBand ai-pipeline.service` and confirm new assertions fail.
- [ ] **Step 6: Implement** style retrieval/context passing through the existing memory retrieval path; add a distinct retrieval constraint only if the existing contact scoping and memory kinds cannot meet the design.
- [ ] **Step 7: Run** `cd backend && npm test -- --runInBand context-builder ai-pipeline.service pgvector-memory-retriever` and confirm PASS.

## Task 3: Verify capture-to-suggestion lifecycle and privacy

**Files:**
- Modify: `backend/src/conversations/conversations.service.spec.ts` only if capture behavior needs a missing regression test.
- Modify: `backend/src/keyboard/keyboard.service.spec.ts` only if the pipeline context handoff needs coverage.
- Test: relevant existing integration/e2e specs, or add `backend/test/keyboard-memory.e2e-spec.ts` cases if DB integration infrastructure is available.
- Docs: `backend/docs/memory-privacy.md` only if style profile semantics add a privacy distinction.

**Interfaces:**
- Consumes: Task 1 conversation-linked style facts and Task 2 contact-scoped reply retrieval.
- Produces: verified end-to-end contract: opted-in labeled capture → role-safe style extraction → same-contact suggestion uses style; opt-out/deletion prevents use and erases source-linked style when no source remains.

- [ ] **Step 1: Add regression tests** for capture labels/merge plus an opted-in mixed-turn capture, same-contact suggestion behavior, cross-contact isolation, and style unavailability after conversation deletion/opt-out.
- [ ] **Step 2: Run** the focused DB-gated test(s) using the repository's configured Postgres test procedure; confirm the lifecycle assertions fail before any needed wiring change.
- [ ] **Step 3: Implement** only the minimum missing lifecycle wiring. Do not change API contracts or persist captures for non-opted-in users.
- [ ] **Step 4: Run** focused tests, then from `backend/` run `npm run lint`, `npm run typecheck`, `npm run build`, and `npm test -- --runInBand`; run relevant e2e tests with migrated Postgres when available.
- [ ] **Step 5: Review** the complete diff for privacy, opt-out/deletion, role attribution, injection, tests, and docs. Report any DB-gated or e2e checks that could not run; do not claim them as passing.
