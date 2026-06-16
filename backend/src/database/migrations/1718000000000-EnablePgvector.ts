import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Baseline migration for the AIChat backend.
 *
 * Enables the `pgvector` extension so later workstreams (WS-2 schema) can use
 * `vector` columns for memory embeddings. Creating the extension is idempotent
 * via `IF NOT EXISTS`, so re-running against a prepared image is safe.
 *
 * It otherwise creates no tables — schema lands in WS-2 — making this the
 * no-op baseline migration referenced in the WS-1 acceptance criteria.
 */
export class EnablePgvector1718000000000 implements MigrationInterface {
  name = 'EnablePgvector1718000000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`CREATE EXTENSION IF NOT EXISTS vector;`);
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP EXTENSION IF EXISTS vector;`);
  }
}
