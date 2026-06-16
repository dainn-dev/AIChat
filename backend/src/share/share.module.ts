import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { ScreenshotsModule } from '../screenshots/screenshots.module';
import { ShareController } from './share.controller';
import { ShareService } from './share.service';

/**
 * Share-Menu backend contract (P3 / DAI-122). Composes `AuthModule` (JWT guard)
 * and `ScreenshotsModule` (reuses ingest + analyze for the shared screenshot).
 */
@Module({
  imports: [AuthModule, ScreenshotsModule],
  controllers: [ShareController],
  providers: [ShareService],
})
export class ShareModule {}
