# AIChat Backend API

The central Backend API hub for the AIChat product — serves the Main App,
Keyboard Extension, and Share Menu Extension (per architecture spec DAI-118).

This repository currently contains **WS-1: the service scaffold & infra
baseline** (epic DAI-119). Auth, the unified AI pipeline, schema, and rate
limiting land in later workstreams.

## Stack

- **NestJS 10** (TypeScript) on Node 22
- **PostgreSQL 16 + pgvector** via TypeORM (migrations, no `synchronize`)
- **S3** object storage for screenshots (AWS SDK v3; MinIO/LocalStack friendly)
- **Observability**: Sentry (errors) + PostHog (product analytics)
- **Jest** unit + e2e tests, ESLint + Prettier, GitHub Actions CI

## Project layout

```
src/
  config/         # typed configuration + env validation
  common/         # error envelope + global exception filter
  database/       # TypeORM data source, module, migrations
  health/         # /health (liveness) + /ready (readiness) probes
  storage/        # S3 client wrapper
  observability/  # Sentry init + PostHog service
  app.module.ts   # root module
  main.ts         # bootstrap
```

## Getting started

```bash
cp .env.example .env        # adjust as needed
npm install

# Start PostgreSQL + pgvector (and optionally the API) locally:
docker compose up -d db

npm run migration:run       # enables the pgvector extension
npm run start:dev           # http://localhost:3000
```

### Endpoints

| Method | Path      | Purpose                                              |
| ------ | --------- | ---------------------------------------------------- |
| GET    | `/health` | Liveness — 200 when the process is up (no DB needed) |
| GET    | `/ready`  | Readiness — 200 when the database is reachable       |

### Error envelope

Every failure responds with the common envelope:

```json
{ "error": { "code": "NOT_FOUND", "message": "Cannot GET /nope" } }
```

`details` is added for field-level validation errors.

## Configuration

All configuration comes from environment variables (validated at boot — see
`src/config/env.validation.ts`). The full set with defaults lives in
[`.env.example`](./.env.example). Optional integrations (S3 credentials, Sentry
DSN, PostHog key) may be left blank in local/dev — the service boots without
them and logs that they are disabled.

## Database & migrations

Schema changes go through TypeORM migrations only (`synchronize` is off). The
baseline migration `EnablePgvector` turns on the `vector` extension; the actual
schema (users, conversations, messages, memories, usage) lands in WS-2.

```bash
npm run migration:run       # apply pending migrations
npm run migration:revert    # roll back the latest
npm run migration:generate -- src/database/migrations/<Name>
```

## Scripts

| Script                  | Description                          |
| ----------------------- | ------------------------------------ |
| `npm run start:dev`     | Watch-mode dev server                |
| `npm run build`         | Compile to `dist/`                   |
| `npm run lint`          | ESLint (zero warnings allowed)       |
| `npm run typecheck`     | `tsc --noEmit`                       |
| `npm test`              | Unit tests                           |
| `npm run test:e2e`      | End-to-end tests (needs a database)  |
| `npm run migration:run` | Apply DB migrations                  |

## CI

`.github/workflows/ci.yml` spins up a `pgvector/pgvector:pg16` service, then
lints, typechecks, builds, runs unit tests, applies migrations, asserts the
`vector` extension is present, and runs the e2e suite.
