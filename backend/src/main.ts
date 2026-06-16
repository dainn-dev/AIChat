import 'reflect-metadata';
import { Logger, ValidationPipe } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import { AppConfig, ObservabilityConfig } from './config/configuration';
import { AllExceptionsFilter } from './common/filters/all-exceptions.filter';
import { initSentry } from './observability/sentry';
import configuration from './config/configuration';

async function bootstrap(): Promise<void> {
  const logger = new Logger('Bootstrap');

  // Init Sentry before the app exists so bootstrap-time errors are captured.
  const bootCfg = configuration();
  initSentry(bootCfg.observability as ObservabilityConfig, bootCfg.app.nodeEnv);

  const app = await NestFactory.create(AppModule, { bufferLogs: false });
  const config = app.get(ConfigService);
  const appCfg = config.getOrThrow<AppConfig>('app');

  app.enableShutdownHooks();
  app.enableCors({ origin: appCfg.corsOrigins });

  app.useGlobalPipes(
    new ValidationPipe({
      whitelist: true,
      forbidNonWhitelisted: true,
      transform: true,
    }),
  );
  app.useGlobalFilters(new AllExceptionsFilter());

  await app.listen(appCfg.port);
  logger.log(
    `AIChat backend listening on port ${appCfg.port} (env=${appCfg.nodeEnv}).`,
  );
}

bootstrap().catch((err) => {
  // eslint-disable-next-line no-console
  console.error('Fatal error during bootstrap', err);
  process.exit(1);
});
