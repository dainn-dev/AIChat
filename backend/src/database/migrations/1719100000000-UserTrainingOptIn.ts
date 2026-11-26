import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Model training consent (see docs/model-training.md).
 *
 * Adds `users.training_opt_in` (default false: nobody is opted in until they
 * explicitly enable it via `PATCH /me/settings`) and `training_opt_in_at`, the
 * moment the current opt-in started. The training export only uses messages
 * stored at or after that moment, so turning the switch on never pulls in
 * older history. Both are metadata-only column additions on PG 11+.
 */
export class UserTrainingOptIn1719100000000 implements MigrationInterface {
  name = 'UserTrainingOptIn1719100000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "users" ADD COLUMN "training_opt_in" boolean NOT NULL DEFAULT false;`,
    );
    await queryRunner.query(
      `ALTER TABLE "users" ADD COLUMN "training_opt_in_at" timestamptz;`,
    );
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "users" DROP COLUMN IF EXISTS "training_opt_in_at";`,
    );
    await queryRunner.query(
      `ALTER TABLE "users" DROP COLUMN IF EXISTS "training_opt_in";`,
    );
  }
}
