import { HttpException, HttpStatus } from '@nestjs/common';
import { UsageMetric } from '../usage.constants';

/**
 * Thrown when a finite daily quota (e.g. Free = 20 replies/day) is reached.
 *
 * Mapped to HTTP 429 with a stable `QUOTA_EXCEEDED` code. The response object
 * carries `code`/`details`, which `AllExceptionsFilter` surfaces in the common
 * error envelope so clients can render which metric was exhausted.
 */
export class QuotaExceededException extends HttpException {
  constructor(metric: UsageMetric, limit: number) {
    super(
      {
        code: 'QUOTA_EXCEEDED',
        message: `Daily ${metric} limit reached. Upgrade to Pro for more.`,
        details: { metric, limit, scope: 'daily' },
      },
      HttpStatus.TOO_MANY_REQUESTS,
    );
  }
}
