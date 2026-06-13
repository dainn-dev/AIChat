import { Type } from 'class-transformer';
import {
  ArrayMaxSize,
  ArrayMinSize,
  IsArray,
  IsEnum,
  IsInt,
  IsOptional,
  IsString,
  Max,
  MaxLength,
  Min,
  ValidateNested,
} from 'class-validator';
import { AiMessageDto } from './ai-message.dto';
import { Tone } from '../enums/tone.enum';

/**
 * `POST /ai/reply` request (DAI-124 §3, FR-R1/R3). Phase 1 accepts an inline
 * `messages[]` conversation; the `conversation_id` variant (stored lookup) is
 * gated on WS-2 entities + WS-3 auth and added there.
 */
export class ReplyRequestDto {
  @IsArray()
  @ArrayMinSize(1)
  @ArrayMaxSize(200)
  @ValidateNested({ each: true })
  @Type(() => AiMessageDto)
  messages!: AiMessageDto[];

  @IsEnum(Tone)
  tone!: Tone;

  @IsOptional()
  @IsString()
  @MaxLength(500)
  user_goal?: string;

  @IsOptional()
  @IsString()
  @MaxLength(100)
  relationship_stage?: string;

  /** Number of candidate replies to return (FR-R3, default 3). */
  @IsOptional()
  @IsInt()
  @Min(1)
  @Max(5)
  count?: number;
}

export interface ReplyCandidate {
  tone: Tone;
  text: string;
}

export interface ReplyResponse {
  replies: ReplyCandidate[];
  /**
   * Per-tier usage snapshot. Populated by the Usage/Quota service in WS-6;
   * `null` until that workstream lands so the response shape is stable now.
   */
  usage: UsageSnapshot | null;
}

export interface UsageSnapshot {
  replies_used: number;
  replies_limit: number | null;
  screenshots_used: number;
  screenshots_limit: number | null;
}
