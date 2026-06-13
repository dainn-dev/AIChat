import { Body, Controller, HttpCode, HttpStatus, Post } from '@nestjs/common';
import { AiService } from './ai.service';
import { ReplyRequestDto, ReplyResponse } from './dto/reply.dto';
import { RewriteRequestDto, RewriteResponse } from './dto/rewrite.dto';
import { AnalyzeRequestDto, AnalyzeResponse } from './dto/analyze.dto';

/**
 * Unified AI endpoints (DAI-124 §3): reply / rewrite / analyze.
 *
 * Phase 1 operates on inline `messages[]` / `text` (stateless) — the stored
 * `conversation_id` variant and per-tier quota enforcement land with WS-2/WS-3
 * (persistence + auth) and WS-6 (quota). Auth guards will be applied here once
 * WS-3 ships; until then these are unauthenticated for integration.
 */
@Controller('ai')
export class AiController {
  constructor(private readonly ai: AiService) {}

  @Post('reply')
  @HttpCode(HttpStatus.OK)
  reply(@Body() dto: ReplyRequestDto): Promise<ReplyResponse> {
    return this.ai.reply(dto);
  }

  @Post('rewrite')
  @HttpCode(HttpStatus.OK)
  rewrite(@Body() dto: RewriteRequestDto): Promise<RewriteResponse> {
    return this.ai.rewrite(dto);
  }

  @Post('analyze')
  @HttpCode(HttpStatus.OK)
  analyze(@Body() dto: AnalyzeRequestDto): Promise<AnalyzeResponse> {
    return this.ai.analyze(dto);
  }
}
