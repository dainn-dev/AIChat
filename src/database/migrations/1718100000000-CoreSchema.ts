import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Core schema for the AIChat backend (DAI-126 / WS-2, per DAI-124 §2).
 *
 * Creates the core Phase-1 tables and their constraints in one atomic
 * migration: `users`, `auth_sessions`, `conversations`, `messages`,
 * `ai_requests`, `screenshots`, and `memories`. The owning feature workstreams
 * (auth, conversations, AI pipeline, screenshots) add their TypeORM entities on
 * top of this schema later — `synchronize` stays off, so the schema is defined
 * here and only here.
 *
 * `usage_counters` and `spend_counters` are intentionally NOT created here:
 * they are owned by the earlier `CreateUsageCounters1718000100000` migration
 * (WS-6). Its metric-based `(user_id, metric, usage_date, count)` shape is the
 * single canonical quota store, read by both `UsageService` and `GET /me`.
 *
 * Notes on a few deliberate choices:
 * - `memories.embedding` is declared as an unbounded `vector` (pgvector) and is
 *   nullable. The embedding dimension depends on the Phase-2 embedding model
 *   (DAI-121) and is intentionally NOT hardcoded; rows are populated in Phase 2.
 * - Bounded value sets (tier, message sender, request source/type) use native
 *   Postgres enum types so invalid values are rejected at the database layer.
 */
export class CoreSchema1718100000000 implements MigrationInterface {
  name = 'CoreSchema1718100000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    // gen_random_uuid() is core in PG13+, but pgcrypto is harmless and keeps
    // UUID defaults working on images where it is not yet exposed.
    await queryRunner.query(`CREATE EXTENSION IF NOT EXISTS pgcrypto;`);

    // ── Enum types ────────────────────────────────────────────────────
    await queryRunner.query(`CREATE TYPE "user_tier" AS ENUM ('free', 'pro');`);
    await queryRunner.query(
      `CREATE TYPE "message_sender" AS ENUM ('me', 'them');`,
    );
    await queryRunner.query(
      `CREATE TYPE "content_source" AS ENUM ('app', 'keyboard', 'share', 'ocr');`,
    );
    await queryRunner.query(
      `CREATE TYPE "ai_request_type" AS ENUM ('reply', 'analysis', 'coach');`,
    );

    // ── users ─────────────────────────────────────────────────────────
    await queryRunner.query(`
      CREATE TABLE "users" (
        "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        "email" text NOT NULL,
        "password_hash" text NOT NULL,
        "display_name" text,
        "tier" "user_tier" NOT NULL DEFAULT 'free',
        "created_at" timestamptz NOT NULL DEFAULT now(),
        "updated_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "uq_users_email" UNIQUE ("email")
      );
    `);

    // ── auth_sessions (refresh-token store) ───────────────────────────
    await queryRunner.query(`
      CREATE TABLE "auth_sessions" (
        "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        "user_id" uuid NOT NULL,
        "refresh_token_hash" text NOT NULL,
        "expires_at" timestamptz NOT NULL,
        "revoked_at" timestamptz,
        "created_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "fk_auth_sessions_user" FOREIGN KEY ("user_id")
          REFERENCES "users" ("id") ON DELETE CASCADE,
        CONSTRAINT "uq_auth_sessions_token" UNIQUE ("refresh_token_hash")
      );
    `);
    await queryRunner.query(
      `CREATE INDEX "idx_auth_sessions_user" ON "auth_sessions" ("user_id");`,
    );

    // ── conversations ─────────────────────────────────────────────────
    await queryRunner.query(`
      CREATE TABLE "conversations" (
        "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        "user_id" uuid NOT NULL,
        "platform" text NOT NULL,
        "contact_label" text,
        "relationship_stage" text,
        "created_at" timestamptz NOT NULL DEFAULT now(),
        "updated_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "fk_conversations_user" FOREIGN KEY ("user_id")
          REFERENCES "users" ("id") ON DELETE CASCADE
      );
    `);
    await queryRunner.query(
      `CREATE INDEX "idx_conversations_user" ON "conversations" ("user_id");`,
    );

    // ── messages ──────────────────────────────────────────────────────
    await queryRunner.query(`
      CREATE TABLE "messages" (
        "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        "conversation_id" uuid NOT NULL,
        "sender" "message_sender" NOT NULL,
        "content" text NOT NULL,
        "position" integer NOT NULL,
        "source" "content_source" NOT NULL DEFAULT 'app',
        "created_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "fk_messages_conversation" FOREIGN KEY ("conversation_id")
          REFERENCES "conversations" ("id") ON DELETE CASCADE
      );
    `);
    await queryRunner.query(
      `CREATE INDEX "idx_messages_conversation_position" ON "messages" ("conversation_id", "position");`,
    );

    // ── ai_requests (pipeline audit/history) ──────────────────────────
    await queryRunner.query(`
      CREATE TABLE "ai_requests" (
        "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        "user_id" uuid NOT NULL,
        "conversation_id" uuid,
        "type" "ai_request_type" NOT NULL,
        "source" "content_source" NOT NULL,
        "provider" text,
        "request_payload" jsonb,
        "response_payload" jsonb,
        "tokens_in" integer,
        "tokens_out" integer,
        "latency_ms" integer,
        "status" text,
        "created_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "fk_ai_requests_user" FOREIGN KEY ("user_id")
          REFERENCES "users" ("id") ON DELETE CASCADE,
        CONSTRAINT "fk_ai_requests_conversation" FOREIGN KEY ("conversation_id")
          REFERENCES "conversations" ("id") ON DELETE SET NULL
      );
    `);
    await queryRunner.query(
      `CREATE INDEX "idx_ai_requests_user" ON "ai_requests" ("user_id");`,
    );
    await queryRunner.query(
      `CREATE INDEX "idx_ai_requests_conversation" ON "ai_requests" ("conversation_id");`,
    );

    // ── screenshots ───────────────────────────────────────────────────
    await queryRunner.query(`
      CREATE TABLE "screenshots" (
        "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        "user_id" uuid NOT NULL,
        "conversation_id" uuid,
        "s3_key" text,
        "ocr_text" text,
        "ocr_status" text,
        "created_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "fk_screenshots_user" FOREIGN KEY ("user_id")
          REFERENCES "users" ("id") ON DELETE CASCADE,
        CONSTRAINT "fk_screenshots_conversation" FOREIGN KEY ("conversation_id")
          REFERENCES "conversations" ("id") ON DELETE SET NULL
      );
    `);
    await queryRunner.query(
      `CREATE INDEX "idx_screenshots_user" ON "screenshots" ("user_id");`,
    );

    // ── memories (schema defined now, populated in Phase 2 / DAI-121) ──
    // `embedding` is an unbounded pgvector column on purpose: the dimension is
    // chosen with the Phase-2 embedding model and must not be hardcoded here.
    await queryRunner.query(`
      CREATE TABLE "memories" (
        "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        "user_id" uuid NOT NULL,
        "contact_label" text,
        "kind" text NOT NULL,
        "content" text NOT NULL,
        "embedding" vector,
        "source_ref" text,
        "created_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "fk_memories_user" FOREIGN KEY ("user_id")
          REFERENCES "users" ("id") ON DELETE CASCADE
      );
    `);
    await queryRunner.query(
      `CREATE INDEX "idx_memories_user" ON "memories" ("user_id");`,
    );
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    // Drop in reverse dependency order. Tables with FKs into `users` /
    // `conversations` go first; the enum types go last. `usage_counters` is
    // owned by CreateUsageCounters1718000100000 and dropped by its own `down`.
    await queryRunner.query(`DROP TABLE IF EXISTS "memories";`);
    await queryRunner.query(`DROP TABLE IF EXISTS "screenshots";`);
    await queryRunner.query(`DROP TABLE IF EXISTS "ai_requests";`);
    await queryRunner.query(`DROP TABLE IF EXISTS "messages";`);
    await queryRunner.query(`DROP TABLE IF EXISTS "conversations";`);
    await queryRunner.query(`DROP TABLE IF EXISTS "auth_sessions";`);
    await queryRunner.query(`DROP TABLE IF EXISTS "users";`);

    await queryRunner.query(`DROP TYPE IF EXISTS "ai_request_type";`);
    await queryRunner.query(`DROP TYPE IF EXISTS "content_source";`);
    await queryRunner.query(`DROP TYPE IF EXISTS "message_sender";`);
    await queryRunner.query(`DROP TYPE IF EXISTS "user_tier";`);
  }
}
