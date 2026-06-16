import { Injectable } from '@nestjs/common';
import {
  AiPipelineService,
  PipelineContext,
} from './pipeline/ai-pipeline.service';
import { ReplyRequestDto, ReplyResponse } from './dto/reply.dto';
import { RewriteRequestDto, RewriteResponse } from './dto/rewrite.dto';
import { AnalyzeRequestDto, AnalyzeResponse } from './dto/analyze.dto';
import { AiSource } from './enums/source.enum';

const DEFAULT_REPLY_COUNT = 3;

/**
 * Thin orchestration layer between the `/ai/*` controllers and the pipeline.
 * Maps request DTOs to pipeline inputs and shapes the response contract.
 *
 * `usage` is returned as `null` until WS-6 (Usage/Quota & tiers) wires the
 * counter; `userId` is `undefined` until WS-3 (Auth) provides the authenticated
 * principal. Both are threaded through `PipelineContext` so those workstreams
 * plug in without touching this layer. Phase 1 always emits `source = app`.
 */
@Injectable()
export class AiService {
  constructor(private readonly pipeline: AiPipelineService) {}

  async reply(dto: ReplyRequestDto): Promise<ReplyResponse> {
    const ctx: PipelineContext = { source: AiSource.App };
    const replies = await this.pipeline.generateReplies(
      {
        messages: dto.messages,
        tone: dto.tone,
        count: dto.count ?? DEFAULT_REPLY_COUNT,
        userGoal: dto.user_goal,
        relationshipStage: dto.relationship_stage,
      },
      ctx,
    );
    return { replies, usage: null };
  }

  async rewrite(dto: RewriteRequestDto): Promise<RewriteResponse> {
    const ctx: PipelineContext = { source: AiSource.App };
    const text = await this.pipeline.rewrite(
      { text: dto.text, tone: dto.tone },
      ctx,
    );
    return { text, usage: null };
  }

  async analyze(dto: AnalyzeRequestDto): Promise<AnalyzeResponse> {
    const ctx: PipelineContext = { source: AiSource.App };
    const analysis = await this.pipeline.analyze(
      {
        messages: dto.messages,
        userGoal: dto.user_goal,
        relationshipStage: dto.relationship_stage,
      },
      ctx,
    );
    return { ...analysis, usage: null };
  }
}
