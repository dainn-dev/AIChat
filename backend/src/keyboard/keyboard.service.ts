import { Injectable } from '@nestjs/common';
import { AiPipelineService } from '../ai/pipeline/ai-pipeline.service';
import { AiSource } from '../ai/enums/source.enum';
import { Tier } from '../common/tiers';
import { Tier as UsageTier, UsageMetric } from '../usage/usage.constants';
import { UsageService } from '../usage/usage.service';
import { AuthenticatedUser } from '../auth/jwt-payload';
import { ConversationsService } from '../conversations/conversations.service';
import {
  KeyboardCorrectDto,
  KeyboardReplyDto,
  KeyboardReplyResponse,
  KeyboardRewriteDto,
  KeyboardSummarizeDto,
  KeyboardSummaryResponse,
  KeyboardTextResponse,
  KeyboardTranslateDto,
  KeyboardWriteDto,
} from './dto/keyboard.dto';

const DEFAULT_REPLY_COUNT = 3;

/**
 * Upper bound the reply waits for best-effort conversation persistence
 * (Android Phase 0). Past it the reply returns `conversation_id: null` and the
 * write finishes in the background.
 */
export const PERSIST_TIMEOUT_MS = 1500;

/**
 * Keyboard surface (P4-1 / DAI-136). Thin layer over the AI pipeline that the
 * iOS/Android keyboard extensions consume. Every call:
 *  - is attributed to the authenticated user and tagged `source: keyboard`;
 *  - is metered against the shared per-user daily generation counter (Free=20),
 *    via the reserve→release lifecycle, so a downstream failure refunds the slot;
 *  - returns the live usage snapshot so the keyboard can render remaining quota.
 *
 * `reply` additionally persists the captured conversation for opted-in users
 * (Android Phase 0) — only after a successful generation, best-effort and
 * time-bounded, so it can never fail or noticeably slow the reply.
 */
@Injectable()
export class KeyboardService {
  constructor(
    private readonly pipeline: AiPipelineService,
    private readonly usage: UsageService,
    private readonly conversations: ConversationsService,
  ) {}

  async reply(
    user: AuthenticatedUser,
    dto: KeyboardReplyDto,
  ): Promise<KeyboardReplyResponse> {
    const tier = this.toUsageTier(user.tier);
    const { replies, stickerTags } = await this.usage.runWithQuota(
      user.sub,
      tier,
      UsageMetric.Reply,
      () =>
        this.pipeline.generateRepliesWithStickers(
          {
            messages: dto.conversation,
            tone: dto.tone,
            count: dto.count ?? DEFAULT_REPLY_COUNT,
            lang: dto.lang,
          },
          {
            source: AiSource.Keyboard,
            userId: user.sub,
            // Per-contact memory scoping (MS-3, Android Phase 0).
            contactLabel: dto.contact_label,
          },
        ),
    );
    // Reached only on a successful generation: runWithQuota rethrows (after
    // refunding) when the pipeline fails, so nothing is persisted then.
    const [conversationId, usage] = await Promise.all([
      this.persistCapture(user, dto),
      this.snapshot(user.sub, tier),
    ]);
    return {
      suggestions: replies,
      sticker_tags: stickerTags,
      conversation_id: conversationId,
      usage,
    };
  }

  /**
   * Summary of the captured chat. One metered generation, like a reply; it
   * never persists the conversation (summaries are read-only).
   */
  async summarize(
    user: AuthenticatedUser,
    dto: KeyboardSummarizeDto,
  ): Promise<KeyboardSummaryResponse> {
    const tier = this.toUsageTier(user.tier);
    const analysis = await this.usage.runWithQuota(
      user.sub,
      tier,
      UsageMetric.Reply,
      () =>
        this.pipeline.analyze(
          { messages: dto.conversation, lang: dto.lang },
          {
            source: AiSource.Keyboard,
            userId: user.sub,
            contactLabel: dto.contact_label,
          },
        ),
    );
    return {
      summary: analysis.summary,
      interest_score: analysis.interest_score,
      red_flags: analysis.red_flags,
      degraded: analysis.degraded,
      usage: await this.snapshot(user.sub, tier),
    };
  }

  async rewrite(
    user: AuthenticatedUser,
    dto: KeyboardRewriteDto,
  ): Promise<KeyboardTextResponse> {
    const tier = this.toUsageTier(user.tier);
    const text = await this.usage.runWithQuota(
      user.sub,
      tier,
      UsageMetric.Reply,
      () =>
        this.pipeline.rewrite(
          { text: dto.text, tone: dto.tone, lang: dto.lang },
          { source: AiSource.Keyboard, userId: user.sub },
        ),
    );
    return { text, usage: await this.snapshot(user.sub, tier) };
  }

  async write(
    user: AuthenticatedUser,
    dto: KeyboardWriteDto,
  ): Promise<KeyboardTextResponse> {
    const tier = this.toUsageTier(user.tier);
    const text = await this.usage.runWithQuota(
      user.sub,
      tier,
      UsageMetric.Reply,
      () =>
        this.pipeline.write(
          { instruction: dto.instruction, lang: dto.lang },
          { source: AiSource.Keyboard, userId: user.sub },
        ),
    );
    return { text, usage: await this.snapshot(user.sub, tier) };
  }

  async correct(
    user: AuthenticatedUser,
    dto: KeyboardCorrectDto,
  ): Promise<KeyboardTextResponse> {
    const tier = this.toUsageTier(user.tier);
    const text = await this.usage.runWithQuota(
      user.sub,
      tier,
      UsageMetric.Reply,
      () =>
        this.pipeline.correct(
          { text: dto.text },
          { source: AiSource.Keyboard, userId: user.sub },
        ),
    );
    return { text, usage: await this.snapshot(user.sub, tier) };
  }

  async translate(
    user: AuthenticatedUser,
    dto: KeyboardTranslateDto,
  ): Promise<KeyboardTextResponse> {
    const tier = this.toUsageTier(user.tier);
    const text = await this.usage.runWithQuota(
      user.sub,
      tier,
      UsageMetric.Reply,
      () =>
        this.pipeline.translate(
          {
            text: dto.text,
            targetLang: dto.target_lang,
            sourceLang: dto.source_lang,
          },
          { source: AiSource.Keyboard, userId: user.sub },
        ),
    );
    return { text, usage: await this.snapshot(user.sub, tier) };
  }

  /** Best-effort, time-bounded persistence; resolves `null` instead of failing. */
  private async persistCapture(
    user: AuthenticatedUser,
    dto: KeyboardReplyDto,
  ): Promise<string | null> {
    const write = this.conversations
      .persistKeyboardCapture({
        userId: user.sub,
        platform: dto.platform,
        contactLabel: dto.contact_label,
        remember: dto.remember,
        messages: dto.conversation,
      })
      .catch(() => null); // defensive: the service already never throws
    let timer: NodeJS.Timeout | undefined;
    const timeout = new Promise<null>((resolve) => {
      timer = setTimeout(() => resolve(null), PERSIST_TIMEOUT_MS);
    });
    try {
      return await Promise.race([write, timeout]);
    } finally {
      clearTimeout(timer);
    }
  }

  private snapshot(userId: string, tier: UsageTier) {
    return this.usage.getUsageSummary(userId, tier);
  }

  private toUsageTier(tier: Tier): UsageTier {
    return tier === Tier.Pro ? UsageTier.Pro : UsageTier.Free;
  }
}
