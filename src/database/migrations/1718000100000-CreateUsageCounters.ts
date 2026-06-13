import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * WS-6 — Usage/Quota & tiers.
 *
 * Creates the two tables that back daily quota enforcement and the spend cap:
 *
 *  - `usage_counters`  — one row per (user, metric, UTC day). The UNIQUE
 *    constraint is load-bearing: it is what makes the
 *    `INSERT ... ON CONFLICT DO UPDATE ... WHERE count < limit` reservation in
 *    UsageService atomic, so concurrent requests cannot race past the limit.
 *  - `spend_counters`  — one row per (user, UTC day) accumulating LLM spend in
 *    micro-USD for the Pro abuse ceiling (DAI-124 §5.10).
 *
 * Daily reset is implicit: a new UTC `usage_date` is simply a new key, so no
 * scheduled job is needed. `user_id` carries no FK to `users` — that table is
 * owned by a sibling workstream with no guaranteed migration ordering.
 */
export class CreateUsageCounters1718000100000 implements MigrationInterface {
  name = 'CreateUsageCounters1718000100000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`
      CREATE TABLE "usage_counters" (
        "id" uuid NOT NULL DEFAULT gen_random_uuid(),
        "user_id" uuid NOT NULL,
        "metric" text NOT NULL,
        "usage_date" date NOT NULL,
        "count" integer NOT NULL DEFAULT 0,
        "created_at" timestamptz NOT NULL DEFAULT now(),
        "updated_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "usage_counters_pk" PRIMARY KEY ("id"),
        CONSTRAINT "usage_counters_metric_check"
          CHECK ("metric" IN ('reply', 'screenshot')),
        CONSTRAINT "usage_counters_count_nonneg" CHECK ("count" >= 0),
        CONSTRAINT "usage_counters_unique"
          UNIQUE ("user_id", "metric", "usage_date")
      );
    `);
    await queryRunner.query(`
      CREATE INDEX "idx_usage_counters_user_date"
        ON "usage_counters" ("user_id", "usage_date");
    `);

    await queryRunner.query(`
      CREATE TABLE "spend_counters" (
        "id" uuid NOT NULL DEFAULT gen_random_uuid(),
        "user_id" uuid NOT NULL,
        "usage_date" date NOT NULL,
        "spent_micro_usd" bigint NOT NULL DEFAULT 0,
        "created_at" timestamptz NOT NULL DEFAULT now(),
        "updated_at" timestamptz NOT NULL DEFAULT now(),
        CONSTRAINT "spend_counters_pk" PRIMARY KEY ("id"),
        CONSTRAINT "spend_counters_nonneg" CHECK ("spent_micro_usd" >= 0),
        CONSTRAINT "spend_counters_unique" UNIQUE ("user_id", "usage_date")
      );
    `);
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP TABLE "spend_counters";`);
    await queryRunner.query(`DROP INDEX "idx_usage_counters_user_date";`);
    await queryRunner.query(`DROP TABLE "usage_counters";`);
  }
}
