import { QueryRunner } from 'typeorm';
import { UserMemoryOptIn1718700000000 } from './1718700000000-UserMemoryOptIn';

/** DDL assertions for the Android Phase 0 memory opt-in migration. */
describe('UserMemoryOptIn1718700000000', () => {
  const record = async (direction: 'up' | 'down'): Promise<string> => {
    const queries: string[] = [];
    const queryRunner = {
      query: jest.fn((sql: string) => {
        queries.push(sql);
        return Promise.resolve();
      }),
    } as unknown as QueryRunner;
    await new UserMemoryOptIn1718700000000()[direction](queryRunner);
    return queries.join('\n');
  };

  it('adds memory_opt_in as NOT NULL DEFAULT false (opted out by default)', async () => {
    expect(await record('up')).toMatch(
      /ALTER TABLE "users" ADD COLUMN "memory_opt_in" boolean NOT NULL DEFAULT false/,
    );
  });

  it('drops the column on down', async () => {
    expect(await record('down')).toMatch(
      /ALTER TABLE "users" DROP COLUMN IF EXISTS "memory_opt_in"/,
    );
  });
});
