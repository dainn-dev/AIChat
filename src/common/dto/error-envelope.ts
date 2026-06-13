/**
 * Canonical error envelope returned by every endpoint on failure.
 *
 *   { "error": { "code": "...", "message": "...", "details"?: ... } }
 *
 * `code` is a stable, machine-readable identifier (e.g. `VALIDATION_ERROR`,
 * `NOT_FOUND`, `INTERNAL_ERROR`). `message` is human-readable. `details` is an
 * optional, free-form payload for field-level errors or extra context.
 */
export interface ErrorEnvelope {
  error: {
    code: string;
    message: string;
    details?: unknown;
  };
}

export function buildErrorEnvelope(
  code: string,
  message: string,
  details?: unknown,
): ErrorEnvelope {
  return {
    error: {
      code,
      message,
      ...(details !== undefined ? { details } : {}),
    },
  };
}
