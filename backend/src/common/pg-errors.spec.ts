import { ServiceUnavailableException } from '@nestjs/common';
import {
  erasureTimeoutAsUnavailable,
  pgErrorCode,
  retryOnceOnDeadlock,
} from './pg-errors';

const pgError = (code: string) =>
  Object.assign(new Error(`pg ${code}`), { code });

describe('pg-errors', () => {
  it('reads the SQLSTATE from a driver error', () => {
    expect(pgErrorCode(pgError('40P01'))).toBe('40P01');
    expect(pgErrorCode(new Error('x'))).toBeUndefined();
    expect(pgErrorCode(null)).toBeUndefined();
  });

  describe('retryOnceOnDeadlock', () => {
    it('retries exactly once after a deadlock', async () => {
      const work = jest
        .fn()
        .mockRejectedValueOnce(pgError('40P01'))
        .mockResolvedValueOnce('ok');
      await expect(retryOnceOnDeadlock(work)).resolves.toBe('ok');
      expect(work).toHaveBeenCalledTimes(2);
    });

    it('rethrows a second deadlock', async () => {
      const work = jest.fn().mockRejectedValue(pgError('40P01'));
      await expect(retryOnceOnDeadlock(work)).rejects.toThrow('pg 40P01');
      expect(work).toHaveBeenCalledTimes(2);
    });

    it('does not retry other errors', async () => {
      const work = jest.fn().mockRejectedValue(pgError('23505'));
      await expect(retryOnceOnDeadlock(work)).rejects.toThrow('pg 23505');
      expect(work).toHaveBeenCalledTimes(1);
    });
  });

  it('maps lock/statement timeouts to 503 and leaves other errors alone', () => {
    expect(erasureTimeoutAsUnavailable(pgError('55P03'))).toBeInstanceOf(
      ServiceUnavailableException,
    );
    expect(erasureTimeoutAsUnavailable(pgError('57014'))).toBeInstanceOf(
      ServiceUnavailableException,
    );
    const other = pgError('40P01');
    expect(erasureTimeoutAsUnavailable(other)).toBe(other);
  });
});
