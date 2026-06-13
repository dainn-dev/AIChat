import {
  Injectable,
  Logger,
  OnApplicationShutdown,
  OnModuleInit,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { PostHog } from 'posthog-node';
import { ObservabilityConfig } from '../config/configuration';

/**
 * Server-side PostHog client for product analytics. When no API key is
 * configured (local/dev), all calls are silently dropped so feature code can
 * call `capture()` unconditionally.
 */
@Injectable()
export class PosthogService implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(PosthogService.name);
  private client?: PostHog;

  constructor(private readonly config: ConfigService) {}

  onModuleInit(): void {
    const cfg = this.config.getOrThrow<ObservabilityConfig>('observability');
    if (!cfg.posthogApiKey) {
      this.logger.log('PostHog API key not set; product analytics disabled.');
      return;
    }
    this.client = new PostHog(cfg.posthogApiKey, { host: cfg.posthogHost });
    this.logger.log('PostHog initialized.');
  }

  capture(
    distinctId: string,
    event: string,
    properties?: Record<string, unknown>,
  ): void {
    this.client?.capture({ distinctId, event, properties });
  }

  async onApplicationShutdown(): Promise<void> {
    await this.client?.shutdown();
  }
}
