import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Android Phase 0 — record which surface created a conversation.
 *
 * Adds `conversations.source` (reusing the CoreSchema `content_source` enum:
 * app | keyboard | share | ocr) so the keyboard's find-or-create only ever
 * merges into keyboard-created conversations and never into a screenshot /
 * share conversation for the same contact, and so memory extraction can apply
 * the keyboard-only opt-in gate.
 *
 * Backfill of existing rows (first matching rule wins):
 *  1. `share`  — an `ai_requests` row tagged `share` references it. (Best
 *     effort: `AiRequestLogger` does not persist `ai_requests` yet, so
 *     historical share conversations fall through to rule 2/3 → `ocr`.)
 *  2. the `source` of its first message (lowest `position`) — `ocr` for
 *     screenshot/share ingests, `keyboard` for keyboard captures;
 *  3. `ocr`    — it has a screenshot row but no messages;
 *  4. `app`    — anything else (also the column default for new rows).
 */
export class ConversationSource1718800000000 implements MigrationInterface {
  name = 'ConversationSource1718800000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "conversations" ADD COLUMN "source" "content_source";`,
    );

    await queryRunner.query(`
      UPDATE "conversations" c SET "source" = 'share'
       WHERE EXISTS (
         SELECT 1 FROM "ai_requests" r
          WHERE r."conversation_id" = c."id" AND r."source" = 'share'
       );
    `);
    await queryRunner.query(`
      UPDATE "conversations" c SET "source" = first_msg."source"
        FROM (
          SELECT DISTINCT ON ("conversation_id") "conversation_id", "source"
            FROM "messages"
           ORDER BY "conversation_id", "position" ASC
        ) first_msg
       WHERE c."source" IS NULL AND first_msg."conversation_id" = c."id";
    `);
    await queryRunner.query(`
      UPDATE "conversations" c SET "source" = 'ocr'
       WHERE c."source" IS NULL
         AND EXISTS (SELECT 1 FROM "screenshots" s WHERE s."conversation_id" = c."id");
    `);
    await queryRunner.query(
      `UPDATE "conversations" SET "source" = 'app' WHERE "source" IS NULL;`,
    );

    await queryRunner.query(`
      ALTER TABLE "conversations"
        ALTER COLUMN "source" SET DEFAULT 'app',
        ALTER COLUMN "source" SET NOT NULL;
    `);

    // Keyboard find-or-create lookup: (user_id, source, platform, contact_label).
    await queryRunner.query(`
      CREATE INDEX "idx_conversations_user_source_contact"
        ON "conversations" ("user_id", "source", "platform", "contact_label");
    `);
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `DROP INDEX IF EXISTS "idx_conversations_user_source_contact";`,
    );
    await queryRunner.query(
      `ALTER TABLE "conversations" DROP COLUMN IF EXISTS "source";`,
    );
  }
}
