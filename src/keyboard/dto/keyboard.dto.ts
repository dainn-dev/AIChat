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
  MinLength,
  ValidateNested,
} from 'class-validator';
import { AiMessageDto } from '../../ai/dto/ai-message.dto';
import { ReplyCandidate, UsageSnapshot } from '../../ai/dto/reply.dto';
import { Tone } from '../../ai/enums/tone.enum';

/**
 * Keyboard backend contract (P4-1 / DAI-136). Short payloads for near-instant
 * keyboard UX; every call is auth'd, quota-attributed to the shared per-user
 * daily counter, and tagged `source: "keyboard"` server-side.
 */

/** `POST /v1/keyboard/reply` — N tone-tagged suggestions for a conversation. */
export class KeyboardReplyDto {
  @IsOptional()
  @IsString()
  @MaxLength(64)
  platform?: string;

  @IsArray()
  @ArrayMinSize(1)
  @ArrayMaxSize(100)
  @ValidateNested({ each: true })
  @Type(() => AiMessageDto)
  conversation!: AiMessageDto[];

  @IsEnum(Tone)
  tone!: Tone;

  @IsOptional()
  @IsInt()
  @Min(1)
  @Max(5)
  count?: number;
}

/** `POST /v1/keyboard/rewrite` — rewrite a draft in a tone. */
export class KeyboardRewriteDto {
  @IsString()
  @MinLength(1)
  @MaxLength(2000)
  text!: string;

  @IsEnum(Tone)
  tone!: Tone;
}

/** `POST /v1/keyboard/translate` — translate a draft into a target language. */
export class KeyboardTranslateDto {
  @IsString()
  @MinLength(1)
  @MaxLength(2000)
  text!: string;

  /** BCP-47-ish target language code or name, e.g. "es", "vi", "Spanish". */
  @IsString()
  @MinLength(2)
  @MaxLength(32)
  target_lang!: string;

  /** Optional source language; omit for auto-detect. */
  @IsOptional()
  @IsString()
  @MaxLength(32)
  source_lang?: string;
}

export interface KeyboardReplyResponse {
  suggestions: ReplyCandidate[];
  usage: UsageSnapshot;
}

export interface KeyboardTextResponse {
  text: string;
  usage: UsageSnapshot;
}
