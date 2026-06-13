import { HttpException, HttpStatus } from '@nestjs/common';

/**
 * Thrown when a screenshot yields no readable text/messages — a blank or
 * garbled image (AC-O2). Mapped to HTTP 422 with a stable `OCR_FAILED` code.
 *
 * Crucially this is raised *before* any quota reservation, so a failed OCR
 * read never increments the user's daily screenshot counter.
 */
export class OcrFailedException extends HttpException {
  constructor() {
    super(
      {
        code: 'OCR_FAILED',
        message:
          'No readable text could be extracted from the screenshot. The image may be blank or unreadable.',
        details: { scope: 'screenshot' },
      },
      HttpStatus.UNPROCESSABLE_ENTITY,
    );
  }
}
