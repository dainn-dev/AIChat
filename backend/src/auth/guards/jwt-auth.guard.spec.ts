import { ExecutionContext, UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { Tier } from '../../common/tiers';
import { AccessTokenPayload } from '../jwt-payload';
import { JwtAuthGuard } from './jwt-auth.guard';

const SECRET = 'test-secret';

function contextWithAuthHeader(header?: string): {
  ctx: ExecutionContext;
  request: { headers: Record<string, string>; user?: AccessTokenPayload };
} {
  const request: {
    headers: Record<string, string>;
    user?: AccessTokenPayload;
  } = { headers: header ? { authorization: header } : {} };
  const ctx = {
    switchToHttp: () => ({ getRequest: () => request }),
  } as unknown as ExecutionContext;
  return { ctx, request };
}

describe('JwtAuthGuard', () => {
  let guard: JwtAuthGuard;
  let jwt: JwtService;

  beforeEach(() => {
    jwt = new JwtService({});
    const config = {
      getOrThrow: jest.fn().mockReturnValue({ jwtAccessSecret: SECRET }),
    } as unknown as ConfigService;
    guard = new JwtAuthGuard(jwt, config);
  });

  it('allows a valid bearer token and attaches the payload', () => {
    const payload: AccessTokenPayload = {
      sub: 'user-1',
      email: 'a@b.com',
      tier: Tier.Free,
    };
    const token = jwt.sign(payload, { secret: SECRET, expiresIn: '15m' });
    const { ctx, request } = contextWithAuthHeader(`Bearer ${token}`);

    expect(guard.canActivate(ctx)).toBe(true);
    expect(request.user?.sub).toBe('user-1');
  });

  it('rejects a request with no Authorization header (AC-A5)', () => {
    const { ctx } = contextWithAuthHeader();
    expect(() => guard.canActivate(ctx)).toThrow(UnauthorizedException);
  });

  it('rejects a non-bearer scheme', () => {
    const { ctx } = contextWithAuthHeader('Basic abc123');
    expect(() => guard.canActivate(ctx)).toThrow(UnauthorizedException);
  });

  it('rejects a token signed with the wrong secret', () => {
    const token = jwt.sign({ sub: 'user-1' }, { secret: 'other-secret' });
    const { ctx } = contextWithAuthHeader(`Bearer ${token}`);
    expect(() => guard.canActivate(ctx)).toThrow(UnauthorizedException);
  });

  it('rejects an expired token', () => {
    const token = jwt.sign(
      { sub: 'user-1' },
      { secret: SECRET, expiresIn: -1 },
    );
    const { ctx } = contextWithAuthHeader(`Bearer ${token}`);
    expect(() => guard.canActivate(ctx)).toThrow(UnauthorizedException);
  });
});
