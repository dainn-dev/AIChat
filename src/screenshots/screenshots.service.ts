import {
  Injectable,
  Logger,
  NotFoundException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { DataSource, EntityManager } from 'typeorm';
import { AnalyzeResponse } from '../ai/dto/analyze.dto';
import { AiMessageDto } from '../ai/dto/ai-message.dto';
import { AiSource, MessageSender } from '../ai/enums/source.enum';
import { AiPipelineService } from '../ai/pipeline/ai-pipeline.service';
import { Tier } from '../common/tiers';
import { Tier as UsageTier, UsageMetric } from '../usage/usage.constants';
import { UsageService } from '../usage/usage.service';
import { CreateScreenshotDto } from './dto/create-screenshot.dto';
import {
  CreateScreenshotResponse,
  ExtractedMessageView,
} from './dto/screenshot-response';
import { OcrFailedException } from './exceptions/ocr-failed.exception';

/**
 * Screenshot upload + OCR ingestion (WS-5 / DAI-129).
 *
 * Per epic Decision #2 the OCR runs on-device (ML Kit); the server only
 * validates and persists the client-supplied `{ocr_text, extracted_messages}`,
 * and per the retention policy it stores **no raw image** (`s3_key` stays
 * NULL). Ingestion is metered against the daily screenshot quota through
 * {@link UsageService}'s reserve→release lifecycle, so the counter advances
 * only on a successful store (AC-O1) and never on the OCR-failure path (AC-O2)
 * or once the limit is reached (AC-O3).
 */
@Injectable()
export class ScreenshotsService {
  private readonly logger = new Logger(ScreenshotsService.name);

  constructor(
    @InjectDataSource() private readonly dataSource: DataSource,
    private readonly usage: UsageService,
    private readonly pipeline: AiPipelineService,
  ) {}

  /** `POST /screenshots` — persist an OCR'd conversation, metered by quota. */
  async ingest(
    userId: string,
    tier: Tier,
    dto: CreateScreenshotDto,
  ): Promise<CreateScreenshotResponse> {
    const messages = this.cleanMessages(dto.extracted_messages);
    const ocrText = dto.ocr_text.trim();

    // AC-O2 — OCR-failure path. A blank/garbled image yields no usable text or
    // messages; reject before reserving quota so the counter never moves.
    if (ocrText.length === 0 || messages.length === 0) {
      throw new OcrFailedException();
    }

    // Don't bill a request that is going to 404 on an unowned conversation.
    if (dto.conversation_id) {
      await this.assertConversationOwned(dto.conversation_id, userId);
    }

    const usageTier = this.toUsageTier(tier);

    // Reserve a screenshot slot, then persist; the reservation is released
    // (refunded) automatically if the transaction throws.
    const { screenshotId, conversationId } = await this.usage.runWithQuota(
      userId,
      usageTier,
      UsageMetric.Screenshot,
      () => this.persist(userId, dto, ocrText, messages),
    );

    return {
      screenshot_id: screenshotId,
      conversation_id: conversationId,
      ocr_text: ocrText,
      extracted_messages: messages,
      usage: await this.usage.getUsageSummary(userId, usageTier),
    };
  }

  /** `POST /screenshots/:id/analyze` — run the AI analysis on a screenshot. */
  async analyze(
    userId: string,
    tier: Tier,
    screenshotId: string,
  ): Promise<AnalyzeResponse> {
    const rows: Array<{ conversation_id: string | null }> =
      await this.dataSource.query(
        `SELECT conversation_id FROM screenshots WHERE id = $1 AND user_id = $2`,
        [screenshotId, userId],
      );
    if (rows.length === 0) {
      throw new NotFoundException({
        code: 'SCREENSHOT_NOT_FOUND',
        message: 'Screenshot not found.',
      });
    }

    const conversationId = rows[0].conversation_id;
    const messages = conversationId
      ? await this.loadMessages(conversationId)
      : [];
    if (messages.length === 0) {
      throw new UnprocessableEntityException({
        code: 'EMPTY_CONVERSATION',
        message: 'This screenshot has no extracted messages to analyze.',
      });
    }

    // Analysis is not separately metered — the screenshot was already counted
    // at upload — so this mirrors `/ai/analyze` plus the live usage snapshot.
    const analysis = await this.pipeline.analyze(
      { messages },
      {
        source: AiSource.Ocr,
        userId,
        conversationId: conversationId ?? undefined,
      },
    );
    return {
      ...analysis,
      usage: await this.usage.getUsageSummary(userId, this.toUsageTier(tier)),
    };
  }

  /**
   * Persist conversation (new or appended), messages, and the screenshot row in
   * a single transaction so a partial failure leaves nothing behind (and lets
   * the quota reservation be cleanly refunded).
   */
  private async persist(
    userId: string,
    dto: CreateScreenshotDto,
    ocrText: string,
    messages: ExtractedMessageView[],
  ): Promise<{ screenshotId: string; conversationId: string }> {
    return this.dataSource.transaction(async (manager) => {
      const conversationId =
        dto.conversation_id ??
        (await this.createConversation(manager, userId, dto));
      const startPosition = await this.nextPosition(manager, conversationId);
      await this.insertMessages(
        manager,
        conversationId,
        messages,
        startPosition,
      );
      const screenshotId = await this.insertScreenshot(
        manager,
        userId,
        conversationId,
        ocrText,
      );
      return { screenshotId, conversationId };
    });
  }

  private async createConversation(
    manager: EntityManager,
    userId: string,
    dto: CreateScreenshotDto,
  ): Promise<string> {
    const rows: Array<{ id: string }> = await manager.query(
      `INSERT INTO conversations (user_id, platform, contact_label)
       VALUES ($1, $2, $3)
       RETURNING id`,
      [userId, dto.platform ?? 'unknown', dto.contact_label ?? null],
    );
    return rows[0].id;
  }

  /** Next free `position` so appended screenshots keep message order stable. */
  private async nextPosition(
    manager: EntityManager,
    conversationId: string,
  ): Promise<number> {
    const rows: Array<{ next: number }> = await manager.query(
      `SELECT COALESCE(MAX(position), -1) + 1 AS next
         FROM messages WHERE conversation_id = $1`,
      [conversationId],
    );
    return Number(rows[0].next);
  }

  private async insertMessages(
    manager: EntityManager,
    conversationId: string,
    messages: ExtractedMessageView[],
    startPosition: number,
  ): Promise<void> {
    // One multi-row insert. `position` is assigned in array order starting at
    // `startPosition`; `source` is the literal 'ocr' (content_source) for all.
    const values: string[] = [];
    const params: unknown[] = [];
    messages.forEach((m, i) => {
      const base = i * 4;
      values.push(
        `($${base + 1}, $${base + 2}, $${base + 3}, $${base + 4}, 'ocr')`,
      );
      params.push(conversationId, m.sender, m.content, startPosition + i);
    });

    await manager.query(
      `INSERT INTO messages (conversation_id, sender, content, position, source)
       VALUES ${values.join(', ')}`,
      params,
    );
  }

  private async insertScreenshot(
    manager: EntityManager,
    userId: string,
    conversationId: string,
    ocrText: string,
  ): Promise<string> {
    const rows: Array<{ id: string }> = await manager.query(
      `INSERT INTO screenshots (user_id, conversation_id, s3_key, ocr_text, ocr_status)
       VALUES ($1, $2, NULL, $3, 'succeeded')
       RETURNING id`,
      [userId, conversationId, ocrText],
    );
    return rows[0].id;
  }

  private async assertConversationOwned(
    conversationId: string,
    userId: string,
  ): Promise<void> {
    const rows: Array<{ id: string }> = await this.dataSource.query(
      `SELECT id FROM conversations WHERE id = $1 AND user_id = $2`,
      [conversationId, userId],
    );
    if (rows.length === 0) {
      throw new NotFoundException({
        code: 'CONVERSATION_NOT_FOUND',
        message: 'Conversation not found.',
      });
    }
  }

  private async loadMessages(conversationId: string): Promise<AiMessageDto[]> {
    const rows: Array<{ sender: string; content: string }> =
      await this.dataSource.query(
        `SELECT sender, content FROM messages
          WHERE conversation_id = $1
          ORDER BY position ASC`,
        [conversationId],
      );
    return rows.map((r) => ({
      sender: r.sender as MessageSender,
      content: r.content,
    }));
  }

  /** Drop blank messages and trim content; preserves `me`/`them` attribution. */
  private cleanMessages(input: ExtractedMessageView[]): ExtractedMessageView[] {
    return input
      .map((m) => ({ sender: m.sender, content: m.content.trim() }))
      .filter((m) => m.content.length > 0);
  }

  private toUsageTier(tier: Tier): UsageTier {
    return tier === Tier.Pro ? UsageTier.Pro : UsageTier.Free;
  }
}
