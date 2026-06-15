import { Module } from '@nestjs/common';
import { AiModule } from '../ai/ai.module';
import { AuthModule } from '../auth/auth.module';
import { UsageModule } from '../usage/usage.module';
import { KeyboardController } from './keyboard.controller';
import { KeyboardService } from './keyboard.service';

/**
 * Keyboard backend contract (P4-1 / DAI-136). Composes `AuthModule` (JWT guard),
 * `AiModule` (pipeline: reply/rewrite/translate), and `UsageModule` (shared
 * per-user quota).
 */
@Module({
  imports: [AuthModule, AiModule, UsageModule],
  controllers: [KeyboardController],
  providers: [KeyboardService],
})
export class KeyboardModule {}
