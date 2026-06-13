import {
  ArgumentsHost,
  Catch,
  ExceptionFilter,
  HttpException,
  HttpStatus,
  Logger,
} from '@nestjs/common';
import * as Sentry from '@sentry/node';
import { Request, Response } from 'express';
import { buildErrorEnvelope, ErrorEnvelope } from '../dto/error-envelope';

/**
 * Maps every thrown error onto the common error envelope:
 *   { error: { code, message, details? } }
 *
 * - `HttpException`s keep their status and are translated into a stable `code`.
 * - Anything else becomes a 500 `INTERNAL_ERROR` and is reported to Sentry
 *   (when configured). Internal error messages are never leaked to clients.
 */
@Catch()
export class AllExceptionsFilter implements ExceptionFilter {
  private readonly logger = new Logger(AllExceptionsFilter.name);

  catch(exception: unknown, host: ArgumentsHost): void {
    const ctx = host.switchToHttp();
    const response = ctx.getResponse<Response>();
    const request = ctx.getRequest<Request>();

    const { status, envelope } = this.toEnvelope(exception);

    if (status >= HttpStatus.INTERNAL_SERVER_ERROR) {
      this.logger.error(
        `${request.method} ${request.url} -> ${status}`,
        exception instanceof Error ? exception.stack : String(exception),
      );
      Sentry.captureException(exception);
    }

    response.status(status).json(envelope);
  }

  private toEnvelope(exception: unknown): {
    status: number;
    envelope: ErrorEnvelope;
  } {
    if (exception instanceof HttpException) {
      const status = exception.getStatus();
      const res = exception.getResponse();
      const code = this.codeForStatus(status);

      // Nest validation/built-in errors carry a `message` (string | string[]).
      if (typeof res === 'object' && res !== null) {
        const body = res as Record<string, unknown>;
        const message = Array.isArray(body.message)
          ? 'Request validation failed'
          : ((body.message as string) ?? exception.message);
        const details = Array.isArray(body.message)
          ? { fields: body.message }
          : undefined;
        return {
          status,
          envelope: buildErrorEnvelope(code, message, details),
        };
      }

      return {
        status,
        envelope: buildErrorEnvelope(
          code,
          typeof res === 'string' ? res : exception.message,
        ),
      };
    }

    return {
      status: HttpStatus.INTERNAL_SERVER_ERROR,
      envelope: buildErrorEnvelope(
        'INTERNAL_ERROR',
        'An unexpected error occurred.',
      ),
    };
  }

  private codeForStatus(status: number): string {
    const map: Record<number, string> = {
      [HttpStatus.BAD_REQUEST]: 'BAD_REQUEST',
      [HttpStatus.UNAUTHORIZED]: 'UNAUTHORIZED',
      [HttpStatus.FORBIDDEN]: 'FORBIDDEN',
      [HttpStatus.NOT_FOUND]: 'NOT_FOUND',
      [HttpStatus.CONFLICT]: 'CONFLICT',
      [HttpStatus.UNPROCESSABLE_ENTITY]: 'VALIDATION_ERROR',
      [HttpStatus.TOO_MANY_REQUESTS]: 'RATE_LIMITED',
    };
    return map[status] ?? `HTTP_${status}`;
  }
}
