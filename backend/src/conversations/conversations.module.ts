import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { ConversationsController } from './conversations.controller';
import { ConversationsService } from './conversations.service';

/**
 * Conversation persistence + erasure (Android Phase 0). Exports the service so
 * the keyboard surface can persist opted-in captures. The memory-extraction
 * queue is injected from the global `MemoryModule`.
 */
@Module({
  imports: [AuthModule],
  controllers: [ConversationsController],
  providers: [ConversationsService],
  exports: [ConversationsService],
})
export class ConversationsModule {}
