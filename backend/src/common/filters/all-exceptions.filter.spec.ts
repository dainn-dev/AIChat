import {
  ArgumentsHost,
  BadRequestException,
  HttpException,
} from '@nestjs/common';
import { AllExceptionsFilter } from './all-exceptions.filter';
import { QuotaExceededException } from '../../usage/exceptions/quota-exceeded.exception';
import { UsageMetric } from '../../usage/usage.constants';

function capture(exception: unknown) {
  const json = jest.fn();
  const status = jest.fn().mockReturnValue({ json });
  const host = {
    switchToHttp: () => ({
      getResponse: () => ({ status }),
      getRequest: () => ({ method: 'POST', url: '/ai/reply' }),
    }),
  } as unknown as ArgumentsHost;

  new AllExceptionsFilter().catch(exception, host);
  return {
    status: status.mock.calls[0][0] as number,
    body: json.mock.calls[0][0],
  };
}

describe('AllExceptionsFilter', () => {
  it('surfaces an explicit domain code and details (QUOTA_EXCEEDED)', () => {
    const { status, body } = capture(
      new QuotaExceededException(UsageMetric.Reply, 20),
    );

    expect(status).toBe(429);
    expect(body.error.code).toBe('QUOTA_EXCEEDED');
    expect(body.error.details).toEqual({
      metric: 'reply',
      limit: 20,
      scope: 'daily',
    });
  });

  it('falls back to the status-derived code when none is provided', () => {
    const { status, body } = capture(new HttpException('Nope', 404));
    expect(status).toBe(404);
    expect(body.error.code).toBe('NOT_FOUND');
  });

  it('still maps class-validator array messages to a fields detail', () => {
    const { body } = capture(
      new BadRequestException({ message: ['email must be an email'] }),
    );
    expect(body.error.code).toBe('BAD_REQUEST');
    expect(body.error.message).toBe('Request validation failed');
    expect(body.error.details).toEqual({
      fields: ['email must be an email'],
    });
  });

  it('maps unknown errors to a 500 INTERNAL_ERROR without leaking details', () => {
    const { status, body } = capture(new Error('secret stack detail'));
    expect(status).toBe(500);
    expect(body.error.code).toBe('INTERNAL_ERROR');
    expect(body.error.message).toBe('An unexpected error occurred.');
  });
});
