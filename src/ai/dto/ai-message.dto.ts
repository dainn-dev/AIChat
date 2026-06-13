import { IsEnum, IsString, MaxLength, MinLength } from 'class-validator';
import { MessageSender } from '../enums/source.enum';

/**
 * A single inline conversation message supplied by the client when no stored
 * `conversation_id` is used. Matches the normalized `{sender, content}` shape
 * from DAI-124 §1.2 (FR-P2).
 */
export class AiMessageDto {
  @IsEnum(MessageSender)
  sender!: MessageSender;

  @IsString()
  @MinLength(1)
  @MaxLength(4000)
  content!: string;
}
