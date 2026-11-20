import { QueryRunner } from 'typeorm';
import { ConversationSource1718800000000 } from './1718800000000-ConversationSource';

/** DDL/backfill assertions for the Android Phase 0 conversations.source migration. */
describe('ConversationSource1718800000000', () => {
  const record = async (direction: 'up' | 'down'): Promise<string[]> => {
    const queries: string[] = [];
    const queryRunner = {
      query: jest.fn((sql: string) => {
        queries.push(sql);
        return Promise.resolve();
      }),
    } as unknown as QueryRunner;
    await new ConversationSource1718800000000()[direction](queryRunner);
    return queries;
  };

  it('adds source as content_source, backfills, then makes it NOT NULL DEFAULT app', async () => {
    const q = await record('up');
    const add = q.findIndex((s) =>
      /ADD COLUMN "source" "content_source"/.test(s),
    );
    const notNull = q.findIndex((s) => /SET NOT NULL/.test(s));
    const backfills = q
      .map((s, i) => (/UPDATE "conversations"/.test(s) ? i : -1))
      .filter((i) => i >= 0);

    expect(add).toBe(0);
    expect(backfills).toHaveLength(4);
    expect(Math.max(...backfills)).toBeLessThan(notNull);
    expect(q[notNull]).toMatch(/SET DEFAULT 'app'/);
  });

  it('backfills in priority order share → first message → ocr → app', async () => {
    const q = (await record('up')).join('\n');
    const order = [
      q.indexOf(`SET "source" = 'share'`),
      q.indexOf(`SET "source" = first_msg."source"`),
      q.indexOf(`SET "source" = 'ocr'`),
      q.indexOf(`SET "source" = 'app'`),
    ];
    expect(order.every((i) => i >= 0)).toBe(true);
    expect([...order].sort((a, b) => a - b)).toEqual(order);
  });

  it('indexes the keyboard find-or-create lookup', async () => {
    expect((await record('up')).join('\n')).toMatch(
      /ON "conversations" \("user_id", "source", "platform", "contact_label"\)/,
    );
  });

  it('down drops the index and the column', async () => {
    const q = (await record('down')).join('\n');
    expect(q).toMatch(
      /DROP INDEX IF EXISTS "idx_conversations_user_source_contact"/,
    );
    expect(q).toMatch(/DROP COLUMN IF EXISTS "source"/);
  });
});
