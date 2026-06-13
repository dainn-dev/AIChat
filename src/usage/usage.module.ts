import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { SpendCounter } from './entities/spend-counter.entity';
import { UsageCounter } from './entities/usage-counter.entity';
import { UsageService } from './usage.service';

/**
 * Cross-cutting usage/quota module (WS-6). Exports {@link UsageService} so the
 * AI pipeline (WS-4) and screenshot/OCR flow (WS-5) can gate metered actions
 * and report usage, without importing each other.
 */
@Module({
  imports: [TypeOrmModule.forFeature([UsageCounter, SpendCounter])],
  providers: [UsageService],
  exports: [UsageService],
})
export class UsageModule {}
