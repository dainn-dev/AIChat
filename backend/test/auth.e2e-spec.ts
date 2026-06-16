import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { AppModule } from '../src/app.module';
import { AllExceptionsFilter } from '../src/common/filters/all-exceptions.filter';

/**
 * End-to-end coverage of the auth surface (WS-3 / DAI-127) against a real
 * PostgreSQL (provided by CI's `postgres` service, with migrations applied).
 * Exercises acceptance criteria AC-A1..A5 plus refresh-token rotation and
 * logout revocation.
 */
describe('Auth (e2e)', () => {
  let app: INestApplication;
  const email = `signup-${Date.now()}@example.com`;
  const password = 'password123';

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();

    app = moduleRef.createNestApplication();
    app.useGlobalPipes(
      new ValidationPipe({
        whitelist: true,
        forbidNonWhitelisted: true,
        transform: true,
      }),
    );
    app.useGlobalFilters(new AllExceptionsFilter());
    await app.init();
  });

  afterAll(async () => {
    await app?.close();
  });

  const http = () => request(app.getHttpServer());

  it('AC-A1: signup returns 201 with tokens and a zeroed free-tier user', async () => {
    const res = await http()
      .post('/auth/signup')
      .send({ email, password, display_name: 'E2E User' });

    expect(res.status).toBe(201);
    expect(res.body.user.email).toBe(email);
    expect(res.body.user.tier).toBe('free');
    expect(res.body.user.passwordHash).toBeUndefined();
    expect(res.body.access_token).toEqual(expect.any(String));
    expect(res.body.refresh_token).toEqual(expect.any(String));

    // Counters start at zero with the free-tier limits.
    const me = await http()
      .get('/me')
      .set('Authorization', `Bearer ${res.body.access_token}`);
    expect(me.status).toBe(200);
    expect(me.body.usage).toEqual({
      replies_used: 0,
      replies_limit: 20,
      screenshots_used: 0,
      screenshots_limit: 5,
    });
  });

  it('AC-A2: signing up with an existing email returns 409', async () => {
    const res = await http().post('/auth/signup').send({ email, password });
    expect(res.status).toBe(409);
    expect(res.body.error.code).toBe('CONFLICT');
  });

  it('AC-A3: login with valid credentials returns access + refresh tokens', async () => {
    const res = await http().post('/auth/login').send({ email, password });
    expect(res.status).toBe(200);
    expect(res.body.access_token).toEqual(expect.any(String));
    expect(res.body.refresh_token).toEqual(expect.any(String));
  });

  it('rejects login with a wrong password (401)', async () => {
    const res = await http()
      .post('/auth/login')
      .send({ email, password: 'wrong-password' });
    expect(res.status).toBe(401);
  });

  it('AC-A4: refresh issues a new access token and rotates the refresh token', async () => {
    const login = await http().post('/auth/login').send({ email, password });
    const oldRefresh = login.body.refresh_token;

    const refreshed = await http()
      .post('/auth/refresh')
      .send({ refresh_token: oldRefresh });
    expect(refreshed.status).toBe(200);
    expect(refreshed.body.access_token).toEqual(expect.any(String));
    expect(refreshed.body.refresh_token).toEqual(expect.any(String));
    expect(refreshed.body.refresh_token).not.toBe(oldRefresh);

    // The new access token authenticates /me.
    const me = await http()
      .get('/me')
      .set('Authorization', `Bearer ${refreshed.body.access_token}`);
    expect(me.status).toBe(200);

    // Rotation: the old refresh token can no longer be used.
    const reused = await http()
      .post('/auth/refresh')
      .send({ refresh_token: oldRefresh });
    expect(reused.status).toBe(401);
  });

  it('logout revokes the refresh token (subsequent refresh is 401)', async () => {
    const login = await http().post('/auth/login').send({ email, password });
    const refresh = login.body.refresh_token;

    const out = await http()
      .post('/auth/logout')
      .send({ refresh_token: refresh });
    expect(out.status).toBe(204);

    const afterLogout = await http()
      .post('/auth/refresh')
      .send({ refresh_token: refresh });
    expect(afterLogout.status).toBe(401);
  });

  describe('AC-A5: protected routes require a valid access token', () => {
    it('rejects /me with no token', async () => {
      const res = await http().get('/me');
      expect(res.status).toBe(401);
      expect(res.body.error.code).toBe('UNAUTHORIZED');
    });

    it('rejects /me with a malformed token', async () => {
      const res = await http()
        .get('/me')
        .set('Authorization', 'Bearer not-a-real-jwt');
      expect(res.status).toBe(401);
    });
  });
});
