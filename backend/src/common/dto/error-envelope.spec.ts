import { buildErrorEnvelope } from './error-envelope';

describe('buildErrorEnvelope', () => {
  it('produces the canonical { error: { code, message } } shape', () => {
    expect(buildErrorEnvelope('NOT_FOUND', 'Missing')).toEqual({
      error: { code: 'NOT_FOUND', message: 'Missing' },
    });
  });

  it('omits details when not provided', () => {
    const env = buildErrorEnvelope('BAD_REQUEST', 'Nope');
    expect('details' in env.error).toBe(false);
  });

  it('includes details when provided', () => {
    const env = buildErrorEnvelope('VALIDATION_ERROR', 'Bad', {
      fields: ['email must be an email'],
    });
    expect(env.error.details).toEqual({
      fields: ['email must be an email'],
    });
  });
});
