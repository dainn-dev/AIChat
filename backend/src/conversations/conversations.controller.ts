import {
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  ParseUUIDPipe,
  Query,
  UseGuards,
} from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/jwt-payload';
import { ConversationsService } from './conversations.service';
import {
  ConversationListResponse,
  ListConversationsQueryDto,
} from './dto/list-conversations.dto';

/**
 * Conversation discovery + erasure (Android Phase 0). Owner-scoped: another
 * user's conversation is never listed and is indistinguishable from a missing
 * one on delete (404). Listing is metadata-only (no message content) so a
 * client can always find — and erase — what was stored, even when a reply
 * returned `conversation_id: null` (security H1).
 */
@Controller('conversations')
@UseGuards(JwtAuthGuard)
export class ConversationsController {
  constructor(private readonly conversations: ConversationsService) {}

  @Get()
  list(
    @CurrentUser() user: AuthenticatedUser,
    @Query() query: ListConversationsQueryDto,
  ): Promise<ConversationListResponse> {
    return this.conversations.list(user.sub, query);
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  remove(
    @CurrentUser() user: AuthenticatedUser,
    @Param('id', ParseUUIDPipe) id: string,
  ): Promise<void> {
    return this.conversations.remove(user.sub, id);
  }
}
