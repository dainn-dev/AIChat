import { Injectable, Logger } from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { createWriteStream } from 'fs';
import { once } from 'events';
import { DataSource } from 'typeorm';
import { ContextBuilder } from '../ai/pipeline/context-builder';
import { MessageSender } from '../ai/enums/source.enum';
import { SensitiveDataFilter } from '../memory/privacy/sensitive-data.filter';
import {
  buildExamples,
  DEFAULT_TRAINING_OPTIONS,
  TrainingExampleOptions,
} from './training-examples';

export interface TrainingExportOptions extends TrainingExampleOptions {
  /** Output .jsonl path. */
  outPath: string;
  /** At most this many examples per user, so one heavy user cannot dominate. */
  maxPerUser: number;
}

export const DEFAULT_MAX_PER_USER = 2000;

export interface TrainingExportSummary {
  users: number;
  conversations: number;
  examples: number;
  skippedSensitive: number;
  skippedTooLong: number;
}

/**
 * Builds the fine-tuning dataset (docs/model-training.md) from the
 * conversations of users who turned on `training_opt_in`, using only messages
 * stored at or after their `training_opt_in_at`. The file holds no user id,
 * conversation id, platform or contact label: only the prompt/reply text,
 * after `SensitiveDataFilter` masked identifiers and dropped sensitive windows.
 * Logs counts only.
 */
@Injectable()
export class TrainingExportService {
  private readonly logger = new Logger(TrainingExportService.name);
  private readonly filter = new SensitiveDataFilter();
  private readonly builder = new ContextBuilder();

  constructor(@InjectDataSource() private readonly db: DataSource) {}

  async run(
    options: Partial<TrainingExportOptions> & { outPath: string },
  ): Promise<TrainingExportSummary> {
    // Options left undefined (e.g. a CLI flag not given) keep their default.
    const given = Object.fromEntries(
      Object.entries(options).filter(([, value]) => value !== undefined),
    );
    const opts: TrainingExportOptions = {
      ...DEFAULT_TRAINING_OPTIONS,
      maxPerUser: DEFAULT_MAX_PER_USER,
      ...given,
    } as TrainingExportOptions;
    const conversations: Array<{ id: string; user_id: string }> =
      await this.db.query(
        `SELECT DISTINCT c.id, c.user_id
           FROM conversations c
           JOIN users u ON u.id = c.user_id
           JOIN messages m ON m.conversation_id = c.id
          WHERE u.training_opt_in
            AND u.training_opt_in_at IS NOT NULL
            AND m.created_at >= u.training_opt_in_at
          ORDER BY c.user_id, c.id`,
      );

    const summary: TrainingExportSummary = {
      users: 0,
      conversations: 0,
      examples: 0,
      skippedSensitive: 0,
      skippedTooLong: 0,
    };
    const perUser = new Map<string, number>();
    const out = createWriteStream(opts.outPath, { encoding: 'utf8' });
    try {
      for (const conversation of conversations) {
        const used = perUser.get(conversation.user_id) ?? 0;
        if (used >= opts.maxPerUser) continue;
        // Re-checks the consent per conversation, so a user who opts out
        // while the export runs is left out from then on.
        const rows: Array<{ sender: MessageSender; content: string }> =
          await this.db.query(
            `SELECT m.sender, m.content
               FROM messages m
               JOIN conversations c ON c.id = m.conversation_id
               JOIN users u ON u.id = c.user_id
              WHERE m.conversation_id = $1
                AND u.training_opt_in
                AND m.created_at >= u.training_opt_in_at
              ORDER BY m.position`,
            [conversation.id],
          );
        const { examples, stats } = buildExamples(
          rows,
          this.filter,
          this.builder,
          opts,
        );
        const kept = examples.slice(0, opts.maxPerUser - used);
        for (const example of kept) {
          if (!out.write(JSON.stringify(example) + '\n')) {
            await once(out, 'drain');
          }
        }
        if (!perUser.has(conversation.user_id)) summary.users++;
        perUser.set(conversation.user_id, used + kept.length);
        if (kept.length > 0) summary.conversations++;
        summary.examples += kept.length;
        summary.skippedSensitive += stats.skippedSensitive;
        summary.skippedTooLong += stats.skippedTooLong;
      }
    } finally {
      out.end();
      await once(out, 'finish');
    }
    this.logger.log(
      `Training export: users=${summary.users} conversations=${summary.conversations} ` +
        `examples=${summary.examples} skipped_sensitive=${summary.skippedSensitive} ` +
        `skipped_too_long=${summary.skippedTooLong}`,
    );
    return summary;
  }
}
