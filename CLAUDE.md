# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

Monorepo. Today only `backend/` exists (NestJS 10 / TypeScript / Node 22); a `mobile/` app is planned. All commands below run from `backend/`. CI (`.github/workflows/ci.yml`) also runs with `working-directory: ./backend`.

The backend is the single API hub for three client surfaces: the Main App, the Keyboard Extension, and the Share Menu Extension. Ticket IDs in comments (`DAI-xxx`, `WS-n`, `MS-n`, `AC-xx`) refer to Jira epics, workstreams, and acceptance criteria. Keep using them when you add related code.

Note: `backend/README.md` still says the repo is "WS-1 scaffold only". It is out of date. Auth, the AI pipeline, usage/quota, screenshots, share, keyboard, and the memory engine are all implemented.

## Commands (from `backend/`)

```bash
cp .env.example .env && npm install
docker compose up -d db          # Postgres 16 + pgvector (add `redis` for memory extraction)
npm run migration:run            # required before e2e / start
npm run start:dev                # http://localhost:3000

npm run lint                     # ESLint, --max-warnings 0 (warnings fail CI)
npm run typecheck                # tsc --noEmit
npm run build
npm test                         # unit tests: src/**/*.spec.ts
npm test -- usage.service        # run a single unit test file by name pattern
npm test -- -t "test name"       # run a single test by name
npm run test:e2e                 # test/*.e2e-spec.ts
npm run test:e2e -- share        # run a single e2e file
USAGE_DB_TEST=1 npm test -- usage.service.integration   # DB-gated concurrency test (skipped otherwise)

npm run migration:generate -- src/database/migrations/<Name>
npm run migration:revert
```

CI order: lint → typecheck → build → unit → migrations → it checks that the `vector` extension exists and that a Top-K cosine query plans onto `idx_memories_embedding_hnsw` → e2e. Some e2e specs (`ai`, `health`, `auth`, `keyboard`) build a minimal `TestingModule` with no DB. The memory, screenshot, and share specs need a migrated Postgres.

## Architecture

**Bootstrap** (`src/main.ts`): Sentry is initialized before the Nest app exists. It registers a global `ValidationPipe` (`whitelist`, `forbidNonWhitelisted`, `transform`), so unknown DTO fields are rejected. `AllExceptionsFilter` turns every error into the envelope `{ error: { code, message, details? } }`. E2E tests re-register the same pipe and filter by hand. Do the same in new e2e specs.

**Config**: all env vars are validated at boot (`src/config/env.validation.ts`) and mapped into typed namespaces (`app`, `llm`, `memory`, `usage`, `redis`, …) in `src/config/configuration.ts`. Read them with `config.getOrThrow<XConfig>('ns')`. Optional integrations (S3, Sentry, PostHog, LLM key, Redis) can be left blank, and the app still boots in degraded/stub mode.

**Provider abstraction with keyless stubs**: external AI dependencies sit behind injection tokens chosen by a `useFactory` from config:
- `LLM_PROVIDER` (`src/ai/ai.module.ts`): `openai` when `LLM_API_KEY` is set (`LLM_BASE_URL` points it at a gateway), otherwise a deterministic `StubLlmProvider`.
- Embedding provider (`src/ai/embedding/`) and `MEMORY_EXTRACTION_PROVIDER` (`src/memory/`) follow the same rule.
- `MEMORY_RETRIEVER`: `PgVectorMemoryRetriever`, or the no-op stub when `MEMORY_RETRIEVAL_ENABLED=false`. The pgvector retriever degrades to `[]` on any error.

Tests and local dev run on the stubs, so they never need an API key. New providers should implement the interface in `*-provider.interface.ts` and be added to the factory.

**Unified AI pipeline** (`src/ai/pipeline/ai-pipeline.service.ts`): every surface goes through `AiPipelineService`. The steps are normalize → memory retrieval (top-K, scoped by user/contact) → `ContextBuilder` prompt → provider. Then `OutputValidator` coerces the output, with bounded JSON-repair retries (`LLM_MAX_REPAIR_RETRIES`). Model drift degrades gracefully (fallback reply, original text) and never throws. Only a transport/upstream failure becomes a 502. Every provider call goes through `AiRequestLogger`, tagged with `AiSource` (main/keyboard/share) for attribution. It currently emits a PostHog event and a Sentry breadcrumb only. `persist()` is still a no-op TODO, so no `ai_requests` rows are written yet.

**Surfaces are thin layers over shared services**:
- `keyboard/`: reply/rewrite/translate. Each call is wrapped in `UsageService.runWithQuota` and returns a live usage snapshot.
- `share/`: composes `ScreenshotsService.ingest` and `analyze` with `AiSource.Share`. It does not duplicate logic.
- `screenshots/`: OCR runs on-device in the clients. The backend stores the extracted messages, and an OCR failure returns 422 before any quota is spent.
- `ai/` (`/ai/*`), `auth/` (`/auth/*`, `/me`), `memories/` (user-facing memory dashboard), `health/` (`/health`, `/ready`).

**Usage/quota** (`src/usage/usage.service.ts`): the concurrency contract is reserve → release. `reserve()` is one atomic `INSERT … ON CONFLICT DO UPDATE … WHERE count < limit`, so limits cannot be overshot under concurrent load. `release()` refunds on failure. Always meter new AI or OCR work through `runWithQuota` rather than check-then-increment. Free tier has daily counts. Pro has unlimited counts, meant to be bounded by a daily USD spend cap. However, `assertUnderSpendCap` and `recordSpend` have no callers yet, so the cap is not enforced. `common/tiers.ts` defines the tier enum.

**Auth**: JWT access tokens plus refresh sessions (`auth_sessions`). There is no global guard. Protect a controller with `@UseGuards(JwtAuthGuard)` and get the user with `@CurrentUser()` (`AuthenticatedUser` has `sub` and `tier`). `JwtModule` is registered `global: true` on purpose, because Nest instantiates the guard in each host module (DAI-143).

**Memory engine**:
- **Read side**: `PgVectorMemoryRetriever` runs a cosine search over `memories.embedding` (HNSW index), using `MEMORY_COSINE_THRESHOLD` and a char budget.
- **Write side**: `MemoryModule.register()` is a `@Global` dynamic module. When `MEMORY_EXTRACTION_ENABLED=true` (needs Redis), it wires a BullMQ queue and `MemoryExtractionProcessor`. Otherwise a `NoopMemoryExtractionQueue` is bound so callers stay wiring-complete.
- **Enqueue**: fire-and-forget, debounced per conversation, and any queue error is swallowed. It must never fail or slow the caller's request.
- **Extraction**: `SensitiveDataFilter` drops or redacts sensitive categories *before* embedding or storage. Facts below `MEMORY_HIGH_CONFIDENCE_THRESHOLD` go to `pending_review`.
- **Deletion**: deleting a memory is a hard delete (right to erasure; see `docs/memory-privacy.md`).

**Database**: TypeORM, `synchronize: false`. Every schema change must be a migration in `src/database/migrations/`. Entities are auto-discovered by the `**/*.entity.ts` glob. `data-source-options.ts` is shared by the runtime and the migration CLI (`data-source.ts`). The migrations glob excludes `*.spec.ts`, so you can colocate migration specs. The embedding dimension (`EMBEDDING_DIMENSIONS`, default 1536) must match the `memories.embedding vector(N)` column.

## Docs

`backend/docs/` holds the client↔backend contracts: `share-api.md` and `phase3-integration.md` (Share Menu), `keyboard-api.md` and `phase4-integration.md` (Keyboard), and `memory-privacy.md`. Update the matching doc when you change one of those endpoint contracts.
