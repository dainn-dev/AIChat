import { IsEnum, IsString, MaxLength, MinLength } from 'class-validator';
import { MessageSender } from '../../ai/enums/source.enum';

/**
 * A single message the client extracted from a chat screenshot via on-device
 * OCR (Decision #2 = ML Kit). `sender` is the `me`/`them` attribution the
 * client resolved from bubble alignment; the server trusts and persists it
 * (DAI-124 §1.4, AC-O1). Empty content is rejected — a screenshot that yields
 * no readable messages is the OCR-failure path handled in the service (AC-O2).
 */
export class ExtractedMessageDto {
  @IsEnum(MessageSender)
  sender!: MessageSender;

  @IsString()
  @MinLength(1)
  @MaxLength(4000)
  content!: string;
}
