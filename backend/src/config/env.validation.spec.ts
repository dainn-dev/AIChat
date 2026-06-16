import { validateEnv } from './env.validation';

describe('validateEnv', () => {
  it('accepts an empty config and applies defaults', () => {
    const result = validateEnv({});
    expect(result.PORT).toBe(3000);
    expect(result.NODE_ENV).toBe('development');
  });

  it('coerces string PORT/DATABASE_PORT from the environment', () => {
    const result = validateEnv({ PORT: '3999', DATABASE_PORT: '5432' });
    expect(result.PORT).toBe(3999);
    expect(result.DATABASE_PORT).toBe(5432);
  });

  it('rejects an out-of-range port', () => {
    expect(() => validateEnv({ PORT: '70000' })).toThrow(
      /Invalid environment configuration/,
    );
  });

  it('rejects an unknown NODE_ENV', () => {
    expect(() => validateEnv({ NODE_ENV: 'staging' })).toThrow(
      /Invalid environment configuration/,
    );
  });
});
