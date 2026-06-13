import { Logger } from '@nestjs/common';
import * as Sentry from '@sentry/node';
import { ObservabilityConfig } from '../config/configuration';

const logger = new Logger('Sentry');

/**
 * Initializes Sentry as early as possible (before the Nest app is created) so
 * errors during bootstrap are captured. A no-op when no DSN is configured.
 */
export function initSentry(
  cfg: ObservabilityConfig,
  environment: string,
): void {
  if (!cfg.sentryDsn) {
    logger.log('Sentry DSN not set; error reporting disabled.');
    return;
  }

  Sentry.init({
    dsn: cfg.sentryDsn,
    environment,
    tracesSampleRate: cfg.sentryTracesSampleRate,
  });
  logger.log(`Sentry initialized (env=${environment}).`);
}
