import { Type } from 'class-transformer';
import { IsInt, IsOptional, Max, Min } from 'class-validator';

export const DEFAULT_CONVERSATION_PAGE_SIZE = 20;
export const MAX_CONVERSATION_PAGE_SIZE = 100;

/** `GET /conversations` query (Android Phase 0, security H1). */
export class ListConversationsQueryDto {
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(MAX_CONVERSATION_PAGE_SIZE)
  limit?: number;

  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(0)
  offset?: number;
}

/**
 * Metadata-only view of a stored conversation. Deliberately carries NO
 * message content: it exists so clients can find and erase what was stored.
 */
export interface ConversationSummaryView {
  id: string;
  platform: string;
  contact_label: string | null;
  source: string;
  message_count: number;
  updated_at: string;
}

export interface ConversationListResponse {
  conversations: ConversationSummaryView[];
  total: number;
  limit: number;
  offset: number;
}
