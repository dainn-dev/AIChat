import configuration from './configuration';

describe('configuration', () => {
  const original = process.env.JWT_ACCESS_SECRET;
  afterEach(() => {
    if (original === undefined) delete process.env.JWT_ACCESS_SECRET;
    else process.env.JWT_ACCESS_SECRET = original;
  });

  it('falls back to the dev JWT secret when JWT_ACCESS_SECRET is blank', () => {
    // `.env.example` ships `JWT_ACCESS_SECRET=` — an empty string, not unset.
    process.env.JWT_ACCESS_SECRET = '';
    expect(configuration().auth.jwtAccessSecret).toBe(
      'dev-access-secret-change-me',
    );
  });

  it('uses JWT_ACCESS_SECRET when set', () => {
    process.env.JWT_ACCESS_SECRET = 'real-secret';
    expect(configuration().auth.jwtAccessSecret).toBe('real-secret');
  });
});
