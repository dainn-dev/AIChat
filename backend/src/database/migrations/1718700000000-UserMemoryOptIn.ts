import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Android Phase 0 — user-level memory opt-in.
 *
 * Adds `users.memory_opt_in`. Keyboard-captured conversations are persisted
 * (and fed to the Memory Engine) only when this is true, so it defaults to
 * `false`: existing and new users are opted OUT until they explicitly enable it
 * via `PATCH /me/settings`. Adding a NOT NULL column with a constant default is
 * a metadata-only change on PG 11+, so it does not rewrite `users`.
 */
export class UserMemoryOptIn1718700000000 implements MigrationInterface {
  name = 'UserMemoryOptIn1718700000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "users" ADD COLUMN "memory_opt_in" boolean NOT NULL DEFAULT false;`,
    );
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "users" DROP COLUMN IF EXISTS "memory_opt_in";`,
    );
  }
}
