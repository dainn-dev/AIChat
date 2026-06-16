import { Module } from '@nestjs/common';
import { AiModule } from '../ai/ai.module';
import { AuthModule } from '../auth/auth.module';
import { UsageModule } from '../usage/usage.module';
import { ScreenshotsController } from './screenshots.controller';
import { ScreenshotsService } from './screenshots.service';

/**
 * Screenshot upload + OCR ingestion (WS-5 / DAI-129).
 *
 * Composes existing seams rather than re-implementing them: `AuthModule` for
 * the JWT guard, `UsageModule` for quota reserve/release, and `AiModule` for
 * the analysis pipeline (`/screenshots/:id/analyze`).
 */
@Module({
  imports: [AuthModule, UsageModule, AiModule],
  controllers: [ScreenshotsController],
  providers: [ScreenshotsService],
  // Exported so the Share-Menu surface (P4/P3) can reuse ingest + analyze.
  exports: [ScreenshotsService],
})
export class ScreenshotsModule {}
