import { Module } from '@nestjs/common';
import { EmbeddingModule } from '../ai/embedding/embedding.module';
import { AuthModule } from '../auth/auth.module';
import { MemoriesController } from './memories.controller';
import { MemoriesService } from './memories.service';

/**
 * Memory dashboard API (MS-5 / DAI-150). Composes `AuthModule` (JWT guard) and
 * `EmbeddingModule` (EmbeddingService, for embed-on-create and re-embed-on-edit).
 */
@Module({
  imports: [AuthModule, EmbeddingModule],
  controllers: [MemoriesController],
  providers: [MemoriesService],
})
export class MemoriesModule {}
