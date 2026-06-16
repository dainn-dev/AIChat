import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import configuration from './config/configuration';
import { validateEnv } from './config/env.validation';
import { AiModule } from './ai/ai.module';
import { AuthModule } from './auth/auth.module';
import { DatabaseModule } from './database/database.module';
import { HealthModule } from './health/health.module';
import { ObservabilityModule } from './observability/observability.module';
import { ScreenshotsModule } from './screenshots/screenshots.module';
import { StorageModule } from './storage/storage.module';
import { UsageModule } from './usage/usage.module';
import { KeyboardModule } from './keyboard/keyboard.module';
import { MemoryModule } from './memory/memory.module';
import { ShareModule } from './share/share.module';
import { MemoriesModule } from './memories/memories.module';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      cache: true,
      load: [configuration],
      validate: validateEnv,
    }),
    DatabaseModule,
    StorageModule,
    ObservabilityModule,
    HealthModule,
    UsageModule,
    AiModule,
    AuthModule,
    MemoryModule.register(),
    MemoriesModule,
    KeyboardModule,
    ScreenshotsModule,
    ShareModule,
  ],
})
export class AppModule {}
