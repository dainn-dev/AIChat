import { Module } from '@nestjs/common';
import { ConversationErasureService } from './conversation-erasure.service';

/**
 * Shared conversation erasure (Android Phase 0). A standalone module with no
 * imports so both `ConversationsModule` (`DELETE /conversations/:id`) and
 * `AuthModule` (opt-out purge on `PATCH /me/settings`) can use it without a
 * module cycle (ConversationsModule already imports AuthModule for its guard).
 */
@Module({
  providers: [ConversationErasureService],
  exports: [ConversationErasureService],
})
export class ConversationErasureModule {}
