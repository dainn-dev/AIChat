import { Injectable, Logger } from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { pgErrorCode, retryOnceOnDeadlock } from '../common/pg-errors';
import { ConversationErasureService } from './conversation-erasure.service';

/** Users fetched per page by the legacy opt-out backfill. */
export const LEGACY_PURGE_USER_PAGE = 100;

export interface LegacyPurgeSummary {
  users: number;
  conversations: number;
  memories: number;
  failed: number;
}

export interface LegacyPurgeOptions {
  /** Restrict the run to these users (ops re-runs and tests). */
  userIds?: readonly string[];
}

/**
 * One-off deploy backfill (`npm run purge:legacy-optout`): users who opted
 * out before opt-out erased anything still have keyboard conversations and
 * the memories only they backed. Runs the same purge as
 * `PATCH /me/settings { memory_opt_in: false }`, one user per transaction
 * (same lock order, timeouts and deadlock retry), so a failure or timeout
 * for one user leaves that user unchanged and the run continues.
 *
 * Idempotent: a re-run only finds users who still have keyboard data and
 * are still opted out. Logs counts only, never ids or content.
 */
@Injectable()
export class LegacyOptOutPurgeService {
  private readonly logger = new Logger(LegacyOptOutPurgeService.name);

  constructor(
    @InjectDataSource() private readonly dataSource: DataSource,
    private readonly erasure: ConversationErasureService,
  ) {}

  async run(options: LegacyPurgeOptions = {}): Promise<LegacyPurgeSummary> {
    const summary: LegacyPurgeSummary = {
      users: 0,
      conversations: 0,
      memories: 0,
      failed: 0,
    };
    let cursor = '00000000-0000-0000-0000-000000000000';
    for (;;) {
      const page = await this.candidatePage(cursor, options.userIds);
      if (page.length === 0) break;
      for (const userId of page) {
        try {
          const counts = await this.purgeUser(userId);
          if (counts) {
            summary.users += 1;
            summary.conversations += counts.conversations;
            summary.memories += counts.memories;
          }
        } catch (err) {
          summary.failed += 1;
          this.logger.warn(
            `Legacy opt-out purge failed for one user (sqlstate=${pgErrorCode(err) ?? 'n/a'}).`,
          );
        }
      }
      cursor = page[page.length - 1];
      this.logger.log(
        `Progress: users=${summary.users} conversations=${summary.conversations} memories=${summary.memories} failed=${summary.failed}`,
      );
    }
    this.logger.log(
      `Done: users=${summary.users} conversations=${summary.conversations} memories=${summary.memories} failed=${summary.failed}`,
    );
    return summary;
  }

  /** Opted-out users that still own keyboard conversations, by id. */
  private async candidatePage(
    afterId: string,
    userIds?: readonly string[],
  ): Promise<string[]> {
    const rows: Array<{ id: string }> = await this.dataSource.query(
      `SELECT u.id FROM users u
        WHERE u.memory_opt_in = false
          AND u.id > $1
          AND ($3::uuid[] IS NULL OR u.id = ANY($3::uuid[]))
          AND EXISTS (SELECT 1 FROM conversations c
                       WHERE c.user_id = u.id AND c.source = 'keyboard')
        ORDER BY u.id
        LIMIT $2`,
      [afterId, LEGACY_PURGE_USER_PAGE, userIds ? [...userIds] : null],
    );
    return rows.map((r) => r.id);
  }

  /**
   * Purge one user under the user row lock, re-checking the opt-out there:
   * a user who opted back in since the page was read is skipped (null).
   */
  private purgeUser(userId: string) {
    return retryOnceOnDeadlock(() =>
      this.dataSource.transaction(async (manager) => {
        await this.erasure.applyPurgeTimeouts(manager);
        const rows: Array<{ memory_opt_in: boolean }> = await manager.query(
          `SELECT memory_opt_in FROM users WHERE id = $1 FOR NO KEY UPDATE`,
          [userId],
        );
        if (rows.length === 0 || rows[0].memory_opt_in) return null;
        return this.erasure.purgeKeyboardConversations(manager, userId);
      }),
    );
  }
}
