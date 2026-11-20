import { ServiceUnavailableException } from '@nestjs/common';

/** Postgres SQLSTATEs the erasure paths react to. */
export const PG_DEADLOCK_DETECTED = '40P01';
/** Raised when `lock_timeout` expires. */
export const PG_LOCK_NOT_AVAILABLE = '55P03';
/** Raised when `statement_timeout` expires (also on a user cancel). */
export const PG_QUERY_CANCELED = '57014';

/** SQLSTATE of a driver/TypeORM error, if any (`QueryFailedError.code`). */
export function pgErrorCode(err: unknown): string | undefined {
  const code = (err as { code?: unknown } | null)?.code;
  return typeof code === 'string' ? code : undefined;
}

/**
 * Run `work` (a whole transaction) and retry it ONCE when Postgres aborts it
 * as a deadlock victim (`40P01`). The transaction rolled back, so a retry
 * starts from a clean state. Any other error, or a second deadlock, is
 * rethrown.
 */
export async function retryOnceOnDeadlock<T>(
  work: () => Promise<T>,
): Promise<T> {
  try {
    return await work();
  } catch (err) {
    if (pgErrorCode(err) !== PG_DEADLOCK_DETECTED) throw err;
    return work();
  }
}

/**
 * Map a lock/statement timeout into a retryable `503` so a bounded erasure
 * that ran out of time is not reported as an opaque `500`. Other errors are
 * returned unchanged.
 */
export function erasureTimeoutAsUnavailable(err: unknown): unknown {
  const code = pgErrorCode(err);
  if (code === PG_LOCK_NOT_AVAILABLE || code === PG_QUERY_CANCELED) {
    return new ServiceUnavailableException({
      code: 'ERASURE_TIMEOUT',
      message: 'Erasure timed out; nothing was changed. Please retry.',
    });
  }
  return err;
}
