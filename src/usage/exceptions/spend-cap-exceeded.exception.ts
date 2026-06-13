import { HttpException, HttpStatus } from '@nestjs/common';

/**
 * Thrown when a user's accumulated daily LLM spend has reached their tier's
 * spend cap (the abuse ceiling on Pro "unlimited", DAI-124 §5.10).
 *
 * Mapped to HTTP 402 Payment Required to distinguish a spend-cap stop from an
 * ordinary count quota (429).
 */
export class SpendCapExceededException extends HttpException {
  constructor(capMicroUsd: number) {
    super(
      {
        code: 'SPEND_CAP_EXCEEDED',
        message: 'Daily spend cap reached. Please try again tomorrow.',
        details: {
          scope: 'daily',
          cap_usd: capMicroUsd / 1_000_000,
        },
      },
      HttpStatus.PAYMENT_REQUIRED,
    );
  }
}
