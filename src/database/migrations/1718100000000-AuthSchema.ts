import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * WS-3 (DAI-127) auth schema. Creates only the tables the auth surface owns
 * and reads — `users`, `auth_sessions`, and `usage_counters` (the subset of
 * DAI-124 §2 needed for signup/login/session + `GET /me`).
 *
 * NOTE for WS-2 (DAI-126, full data model): these three tables are created
 * here, so the WS-2 migration should NOT re-create them — it owns the
 * remaining tables (conversations, messages, ai_requests, screenshots,
 * memories) and may extend these via follow-up migrations.
 *
 * `gen_random_uuid()` is a core function in PostgreSQL 13+ (CI runs pg16), so
 * no extra extension is required for UUID primary keys.
 */
export class AuthSchema1718100000000 implements MigrationInterface {
  name = 'AuthSchema1718100000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`
      CREATE TABLE "users" (
        "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        "email" varchar(320) NOT NULL,
        "password_hash" text NOT NULL,
        "display_name" text,
        "tier" varchar(16) NOT NULL DEFAULT 'free',
        "created_at" timestamptz NOT NULL DEFAULT now(),
        "updated_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "uq_users_email" UNIQUE ("email"),
        CONSTRAINT "chk_users_tier" CHECK ("tier" IN ('free', 'pro'))
      );
    `);

    await queryRunner.query(`
      CREATE TABLE "auth_sessions" (
        "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        "user_id" uuid NOT NULL,
        "refresh_token_hash" text NOT NULL,
        "expires_at" timestamptz NOT NULL,
        "revoked_at" timestamptz,
        "created_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "uq_auth_sessions_refresh_token_hash" UNIQUE ("refresh_token_hash"),
        CONSTRAINT "fk_auth_sessions_user" FOREIGN KEY ("user_id")
          REFERENCES "users" ("id") ON DELETE CASCADE
      );
    `);
    await queryRunner.query(
      `CREATE INDEX "idx_auth_sessions_user_id" ON "auth_sessions" ("user_id");`,
    );

    await queryRunner.query(`
      CREATE TABLE "usage_counters" (
        "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        "user_id" uuid NOT NULL,
        "date" date NOT NULL,
        "replies_used" integer NOT NULL DEFAULT 0,
        "screenshots_used" integer NOT NULL DEFAULT 0,
        "created_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "uq_usage_counters_user_date" UNIQUE ("user_id", "date"),
        CONSTRAINT "fk_usage_counters_user" FOREIGN KEY ("user_id")
          REFERENCES "users" ("id") ON DELETE CASCADE
      );
    `);
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP TABLE IF EXISTS "usage_counters";`);
    await queryRunner.query(`DROP TABLE IF EXISTS "auth_sessions";`);
    await queryRunner.query(`DROP TABLE IF EXISTS "users";`);
  }
}
