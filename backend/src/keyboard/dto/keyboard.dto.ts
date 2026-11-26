import { Transform, Type } from 'class-transformer';
import {
  ArrayMaxSize,
  ArrayMinSize,
  IsArray,
  IsBoolean,
  IsEnum,
  IsInt,
  IsOptional,
  IsString,
  Matches,
  Max,
  MaxLength,
  Min,
  MinLength,
  ValidateNested,
} from 'class-validator';
import { AiMessageDto } from '../../ai/dto/ai-message.dto';
import { ReplyCandidate, UsageSnapshot } from '../../ai/dto/reply.dto';
import { Tone } from '../../ai/enums/tone.enum';
import { OutputLang } from '../../ai/enums/output-lang.enum';
import { StickerTag } from '../../ai/enums/sticker-tag';
import {
  NO_NUL_PATTERN,
  PLATFORM_PATTERN,
  sanitizeLabelInput,
} from '../../common/text-sanitize';

/** Upper bound on `contact_label` (Android Phase 0); also the memory scope key. */
export const CONTACT_LABEL_MAX_LENGTH = 128;

/**
 * Keyboard backend contract (P4-1 / DAI-136). Short payloads for near-instant
 * keyboard UX; every call is auth'd, quota-attributed to the shared per-user
 * daily counter, and tagged `source: "keyboard"` server-side.
 */

/** `POST /v1/keyboard/reply` — N tone-tagged suggestions for a conversation. */
export class KeyboardReplyDto {
  /**
   * Chat app identifier, normalized to lower case and restricted to
   * `[a-z0-9._-]{1,64}` (security L2) — it keys stored conversations.
   */
  @IsOptional()
  @Transform(({ value }: { value: unknown }) =>
    typeof value === 'string' ? value.trim().toLowerCase() : value,
  )
  @IsString()
  @Matches(PLATFORM_PATTERN, {
    message: 'platform must match [a-z0-9._-]{1,64}',
  })
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

  /**
   * Output language from the keyboard's EN/VI toggle (Android Phase 0).
   * Omitted → the model replies in the conversation's own language.
   */
  @IsOptional()
  @IsEnum(OutputLang)
  lang?: OutputLang;

  /**
   * Display name of the chat partner as captured by the keyboard (Android
   * Phase 0). Scopes memory retrieval to this contact (MS-3) and keys the
   * stored conversation when the user opted into memory.
   */
  @IsOptional()
  // Control chars / newlines → space, quotes/backticks neutralized, whitespace
  // collapsed (security M3); NUL is left in place so it is rejected below (L2).
  @Transform(({ value }: { value: unknown }) =>
    sanitizeLabelInput(value, Number.MAX_SAFE_INTEGER),
  )
  @IsString()
  @Matches(NO_NUL_PATTERN, {
    message: 'contact_label must not contain NUL characters',
  })
  @MinLength(1)
  @MaxLength(CONTACT_LABEL_MAX_LENGTH)
  contact_label?: string;

  /**
   * Per-request opt-out of conversation persistence (Android Phase 0), e.g. an
   * incognito chat. Only an explicit `false` disables it; persistence still
   * requires the user-level `memory_opt_in` plus `platform` and
   * `contact_label`.
   */
  @IsOptional()
  @IsBoolean()
  remember?: boolean;
}

/** `POST /v1/keyboard/rewrite` — rewrite a draft in a tone. */
export class KeyboardRewriteDto {
  @IsString()
  @MinLength(1)
  @MaxLength(2000)
  text!: string;

  @IsEnum(Tone)
  tone!: Tone;

  /** Output language (Android Phase 0); omitted → keep the draft's language. */
  @IsOptional()
  @IsEnum(OutputLang)
  lang?: OutputLang;
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
  /**
   * 0-3 sticker tags (from `STICKER_TAGS`) describing the mood/intent of the
   * suggestions, produced by the same LLM call. Always present; `[]` when none.
   */
  sticker_tags: StickerTag[];
  /**
   * Stored conversation this capture was merged into (Android Phase 0), or
   * `null` when it was not persisted (not opted in, missing platform /
   * contact_label, `remember: false`, or a best-effort write failure/timeout).
   */
  conversation_id: string | null;
  usage: UsageSnapshot;
}

export interface KeyboardTextResponse {
  text: string;
  usage: UsageSnapshot;
}
