import { Inject, Injectable, Logger } from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { ConfigService } from '@nestjs/config';
import { DataSource } from 'typeorm';
import { MemoryConfig } from '../config/configuration';
import {
  MEMORY_EXTRACTION_PROVIDER,
  MemoryExtractionProvider,
} from './extraction/extraction-provider.interface';
import { MemoryWriterService } from './memory-writer.service';
import { SensitiveDataFilter } from './privacy/sensitive-data.filter';

export interface ExtractionOutcome {
  status: 'extracted' | 'no-conversation' | 'budget-exceeded';
  factsExtracted: number;
  factsWritten: number;
}

/**
 * Orchestrates extraction for one conversation on the worker (MS-4 / DAI-149):
 * load messages → extract facts → sensitive-filter (§5.7) → write. Idempotency
 * (AC-M2) is enforced at the write layer by MS-2's content_hash dedupe index, so
 * re-running an unchanged conversation writes no new rows. Cost is bounded by a
 * per-user daily extraction budget (§5.10); over budget the conversation is
 * shed. Each run records an audit row in `memory_extractions`.
 */
@Injectable()
export class MemoryExtractionService {
  private readonly logger = new Logger(MemoryExtractionService.name);
  private readonly dailyBudget: number;

  constructor(
    @InjectDataSource() private readonly dataSource: DataSource,
    @Inject(MEMORY_EXTRACTION_PROVIDER)
    private readonly extractor: MemoryExtractionProvider,
    private readonly writer: MemoryWriterService,
    private readonly sensitive: SensitiveDataFilter,
    config: ConfigService,
  ) {
    this.dailyBudget =
      config.get<MemoryConfig>('memory')?.extractionDailyBudget ?? 200;
  }

  async extractForConversation(
    conversationId: string,
  ): Promise<ExtractionOutcome> {
    const convRows: Array<{ user_id: string; contact_label: string | null }> =
      await this.dataSource.query(
        `SELECT user_id, contact_label FROM conversations WHERE id = $1`,
        [conversationId],
      );
    if (convRows.length === 0) {
      return { status: 'no-conversation', factsExtracted: 0, factsWritten: 0 };
    }
    const { user_id: userId, contact_label: contactLabel } = convRows[0];

    // Cost control (§5.10): cap new extraction runs per user per UTC day.
    const usedToday = await this.countTodaysExtractions(userId);
    if (usedToday >= this.dailyBudget) {
      this.logger.warn(
        `User ${userId} hit the daily extraction budget (${this.dailyBudget}); shedding conversation ${conversationId}.`,
      );
      return { status: 'budget-exceeded', factsExtracted: 0, factsWritten: 0 };
    }

    const messages: Array<{ sender: string; content: string }> =
      await this.dataSource.query(
        `SELECT sender, content FROM messages
          WHERE conversation_id = $1 ORDER BY position ASC`,
        [conversationId],
      );

    const extracted = await this.extractor.extract({
      messages,
      contactLabel: contactLabel ?? undefined,
    });

    // Sensitive-category filter (§5.7): drop high-risk facts and redact inline
    // PII before anything is embedded or stored.
    const facts = extracted
      .map((f) => {
        const scan = this.sensitive.scan(f.content);
        return scan.dropped ? null : { ...f, content: scan.content };
      })
      .filter((f): f is (typeof extracted)[number] => f !== null);

    let written = 0;
    if (facts.length > 0) {
      ({ written } = await this.writer.writeFacts({
        userId,
        contactLabel: contactLabel ?? undefined,
        facts,
        sourceRef: `conversation:${conversationId}`,
      }));
    }

    // Audit/provenance row (MS-2 memory_extractions shape).
    await this.dataSource.query(
      `INSERT INTO memory_extractions (user_id, conversation_id, model, n_memories)
       VALUES ($1, $2, $3, $4)`,
      [userId, conversationId, this.extractor.name, written],
    );

    return {
      status: 'extracted',
      factsExtracted: extracted.length,
      factsWritten: written,
    };
  }

  /** New extraction runs recorded for this user today (UTC). */
  private async countTodaysExtractions(userId: string): Promise<number> {
    const rows: Array<{ count: string }> = await this.dataSource.query(
      `SELECT count(*)::text AS count FROM memory_extractions
        WHERE user_id = $1 AND created_at >= date_trunc('day', now() AT TIME ZONE 'utc')`,
      [userId],
    );
    return rows.length ? Number(rows[0].count) : 0;
  }
}
