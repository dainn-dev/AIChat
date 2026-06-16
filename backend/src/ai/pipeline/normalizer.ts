import { Injectable } from '@nestjs/common';
import { AiMessageDto } from '../dto/ai-message.dto';
import { MessageSender } from '../enums/source.enum';

export interface NormalizedMessage {
  sender: MessageSender;
  content: string;
}

/**
 * Step 1 of the pipeline (DAI-124 §1.2 FR-P2): clean and structure raw inbound
 * messages into ordered `{sender, content}` turns. Trims whitespace, drops
 * empty turns, and caps a very long thread to the most recent N to stay within
 * model context (edge case: "Large conversation" in DAI-124 §6).
 */
@Injectable()
export class Normalizer {
  /** Keep the most recent N turns when a thread is very long. */
  static readonly MAX_TURNS = 80;

  normalize(messages: AiMessageDto[]): NormalizedMessage[] {
    const cleaned = messages
      .map((m) => ({ sender: m.sender, content: m.content.trim() }))
      .filter((m) => m.content.length > 0);

    if (cleaned.length <= Normalizer.MAX_TURNS) {
      return cleaned;
    }
    return cleaned.slice(cleaned.length - Normalizer.MAX_TURNS);
  }
}
