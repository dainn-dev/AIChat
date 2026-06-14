import { MessageSender } from '../../ai/enums/source.enum';
import { UsageSummary } from '../../usage/dto/usage-summary';

/** A persisted, normalized conversation message echoed back to the client. */
export interface ExtractedMessageView {
  sender: MessageSender;
  content: string;
}

/**
 * `POST /screenshots` response (DAI-124 §3, AC-O1): the stored screenshot id,
 * the conversation it fed (new or existing), the normalized messages, and the
 * caller's post-increment usage snapshot.
 */
export interface CreateScreenshotResponse {
  screenshot_id: string;
  conversation_id: string;
  ocr_text: string;
  extracted_messages: ExtractedMessageView[];
  usage: UsageSummary;
}
