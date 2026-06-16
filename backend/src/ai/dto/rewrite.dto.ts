import { IsEnum, IsString, MaxLength, MinLength } from 'class-validator';
import { Tone } from '../enums/tone.enum';
import { UsageSnapshot } from './reply.dto';

/**
 * `POST /ai/rewrite` request (DAI-124 §3, FR-R2). Rewrites a draft into the
 * chosen tone. Stateless — no conversation required.
 */
export class RewriteRequestDto {
  @IsString()
  @MinLength(1)
  @MaxLength(2000)
  text!: string;

  @IsEnum(Tone)
  tone!: Tone;
}

export interface RewriteResponse {
  text: string;
  usage: UsageSnapshot | null;
}
