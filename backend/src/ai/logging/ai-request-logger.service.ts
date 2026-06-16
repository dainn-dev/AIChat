import { Injectable, Logger } from '@nestjs/common';
import * as Sentry from '@sentry/node';
import { PosthogService } from '../../observability/posthog.service';
import { AiRequestType, AiSource } from '../enums/source.enum';

/**
 * One audited pipeline call. Field names mirror the `ai_requests` table columns
 * (DAI-124 §2 / WS-2) so DB persistence drops in without reshaping callers.
 */
export interface AiRequestRecord {
  userId?: string;
  conversationId?: string;
  type: AiRequestType;
  source: AiSource;
  provider: string;
  model: string;
  tokensIn: number;
  tokensOut: number;
  latencyMs: number;
  status: 'ok' | 'error' | 'degraded';
  errorCode?: string;
}

/**
 * Records every pipeline call to the observability stack — provider, latency,
 * token counts, status (DAI-124 §1.2 FR-P7; WS-4 AC "logs provider/latency/
 * tokens to ai_requests and to Sentry/PostHog").
 *
 * Today it writes to the app log, PostHog (product analytics), and a Sentry
 * breadcrumb. Durable persistence to the `ai_requests` table is intentionally
 * a one-line addition once WS-2 ships that entity — the record shape already
 * matches the columns. See the `persist` seam below.
 */
@Injectable()
export class AiRequestLogger {
  private readonly logger = new Logger(AiRequestLogger.name);

  constructor(private readonly posthog: PosthogService) {}

  async record(record: AiRequestRecord): Promise<void> {
    this.logger.log(
      `ai_request type=${record.type} source=${record.source} ` +
        `provider=${record.provider} model=${record.model} ` +
        `tokens_in=${record.tokensIn} tokens_out=${record.tokensOut} ` +
        `latency_ms=${record.latencyMs} status=${record.status}` +
        (record.errorCode ? ` error=${record.errorCode}` : ''),
    );

    this.posthog.capture(record.userId ?? 'anonymous', 'ai_request', {
      type: record.type,
      source: record.source,
      provider: record.provider,
      model: record.model,
      tokens_in: record.tokensIn,
      tokens_out: record.tokensOut,
      latency_ms: record.latencyMs,
      status: record.status,
      ...(record.errorCode ? { error_code: record.errorCode } : {}),
    });

    Sentry.addBreadcrumb({
      category: 'ai',
      type: 'default',
      level: record.status === 'error' ? 'error' : 'info',
      message: `ai_request:${record.type}`,
      data: { ...record },
    });

    await this.persist(record);
  }

  /**
   * Seam for durable `ai_requests` rows. No-op until WS-2 lands the entity;
   * then inject the repository here and insert `record`.
   */
  private async persist(record: AiRequestRecord): Promise<void> {
    // TODO(WS-2): insert into `ai_requests` once the entity exists.
    void record;
  }
}
