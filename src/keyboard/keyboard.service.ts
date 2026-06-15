import { Injectable } from '@nestjs/common';
import { AiPipelineService } from '../ai/pipeline/ai-pipeline.service';
import { AiSource } from '../ai/enums/source.enum';
import { Tier } from '../common/tiers';
import { Tier as UsageTier, UsageMetric } from '../usage/usage.constants';
import { UsageService } from '../usage/usage.service';
import { AuthenticatedUser } from '../auth/jwt-payload';
import {
  KeyboardReplyDto,
  KeyboardReplyResponse,
  KeyboardRewriteDto,
  KeyboardTextResponse,
  KeyboardTranslateDto,
} from './dto/keyboard.dto';

const DEFAULT_REPLY_COUNT = 3;

/**
 * Keyboard surface (P4-1 / DAI-136). Thin layer over the AI pipeline that the
 * iOS/Android keyboard extensions consume. Every call:
 *  - is attributed to the authenticated user and tagged `source: keyboard`;
 *  - is metered against the shared per-user daily generation counter (Free=20),
 *    via the reserve→release lifecycle, so a downstream failure refunds the slot;
 *  - returns the live usage snapshot so the keyboard can render remaining quota.
 */
@Injectable()
export class KeyboardService {
  constructor(
    private readonly pipeline: AiPipelineService,
    private readonly usage: UsageService,
  ) {}

  async reply(
    user: AuthenticatedUser,
    dto: KeyboardReplyDto,
  ): Promise<KeyboardReplyResponse> {
    const tier = this.toUsageTier(user.tier);
    const suggestions = await this.usage.runWithQuota(
      user.sub,
      tier,
      UsageMetric.Reply,
      () =>
        this.pipeline.generateReplies(
          {
            messages: dto.conversation,
            tone: dto.tone,
            count: dto.count ?? DEFAULT_REPLY_COUNT,
          },
          { source: AiSource.Keyboard, userId: user.sub },
        ),
    );
    return { suggestions, usage: await this.snapshot(user.sub, tier) };
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
          { text: dto.text, tone: dto.tone },
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

  private snapshot(userId: string, tier: UsageTier) {
    return this.usage.getUsageSummary(userId, tier);
  }

  private toUsageTier(tier: Tier): UsageTier {
    return tier === Tier.Pro ? UsageTier.Pro : UsageTier.Free;
  }
}
