import { Type } from 'class-transformer';
import {
  ArrayMaxSize,
  ArrayMinSize,
  IsArray,
  IsOptional,
  IsString,
  MaxLength,
  ValidateNested,
} from 'class-validator';
import { AiMessageDto } from './ai-message.dto';
import { ReplyCandidate, UsageSnapshot } from './reply.dto';

/**
 * `POST /ai/analyze` request (DAI-124 §3, FR-N1). Phase 1 accepts an inline
 * `messages[]` conversation; the stored `conversation_id` variant is gated on
 * WS-2 + WS-3.
 */
export class AnalyzeRequestDto {
  @IsArray()
  @ArrayMinSize(1)
  @ArrayMaxSize(200)
  @ValidateNested({ each: true })
  @Type(() => AiMessageDto)
  messages!: AiMessageDto[];

  @IsOptional()
  @IsString()
  @MaxLength(500)
  user_goal?: string;

  @IsOptional()
  @IsString()
  @MaxLength(100)
  relationship_stage?: string;
}

export interface RedFlag {
  code: string;
  message: string;
}

/**
 * Structured analysis result (DAI-124 §1.4, FR-N1/N2). `interest_score` is
 * always a clamped integer 0-100 (FR-N3 / AC-N2) regardless of model drift.
 */
export interface AnalyzeResponse {
  summary: string;
  interest_score: number;
  suggested_replies: ReplyCandidate[];
  red_flags: RedFlag[];
  /**
   * True when the model output could not be parsed/repaired and a safe
   * degraded result was returned instead of a 500 (FR drift handling).
   */
  degraded: boolean;
  usage: UsageSnapshot | null;
}
