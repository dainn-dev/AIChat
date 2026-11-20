import {
  IsEnum,
  IsString,
  Matches,
  MaxLength,
  MinLength,
} from 'class-validator';
import { NO_NUL_PATTERN } from '../../common/text-sanitize';
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
  // Postgres `text` cannot hold NUL; reject it at the edge (security L2).
  @Matches(NO_NUL_PATTERN, {
    message: 'content must not contain NUL characters',
  })
  content!: string;
}
