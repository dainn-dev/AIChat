import { INestApplication, ValidationPipe } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { JwtModule, JwtService } from '@nestjs/jwt';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import configuration, { AuthConfig } from '../src/config/configuration';
import { AllExceptionsFilter } from '../src/common/filters/all-exceptions.filter';
import { AuthService } from '../src/auth/auth.service';
import { MeController } from '../src/auth/me.controller';
import { ConversationsController } from '../src/conversations/conversations.controller';
import { ConversationsService } from '../src/conversations/conversations.service';
import { KeyboardController } from '../src/keyboard/keyboard.controller';
import { KeyboardService } from '../src/keyboard/keyboard.service';

/**
 * Android Phase 0 HTTP contract — NO database. Real controllers, real
 * JwtAuthGuard, and the same global ValidationPipe/filter as `main.ts`, with
 * the services mocked. Pins request validation (lang, contact_label, remember,
 * PATCH /me/settings, DELETE /conversations/:id) and that validated params
 * reach the service layer. DB behaviour lives in keyboard-memory.e2e-spec.ts.
 */
describe('Android Phase 0 contract (e2e, no DB)', () => {
  let app: INestApplication;
  let token: string;

  const keyboard = {
    reply: jest.fn(),
    rewrite: jest.fn(),
    translate: jest.fn(),
  };
  const auth = { getMe: jest.fn(), updateSettings: jest.fn() };
  const conversations = { remove: jest.fn() };

  const http = () => request(app.getHttpServer());
  const bearer = () => ({ Authorization: `Bearer ${token}` });
  const conversation = [{ sender: 'them', content: 'tối nay rảnh không?' }];

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [
        ConfigModule.forRoot({ isGlobal: true, load: [configuration] }),
        JwtModule.register({ global: true }),
      ],
      controllers: [KeyboardController, MeController, ConversationsController],
      providers: [
        { provide: KeyboardService, useValue: keyboard },
        { provide: AuthService, useValue: auth },
        { provide: ConversationsService, useValue: conversations },
      ],
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

    const { jwtAccessSecret } = app
      .get(ConfigService)
      .getOrThrow<AuthConfig>('auth');
    token = await app
      .get(JwtService)
      .signAsync(
        { sub: 'user-1', email: 'a@b.com', tier: 'free' },
        { secret: jwtAccessSecret, expiresIn: '5m' },
      );
  });

  afterAll(async () => {
    await app?.close();
  });

  beforeEach(() => {
    jest.resetAllMocks();
    keyboard.reply.mockResolvedValue({
      suggestions: [{ tone: 'Friendly', text: 'ok nhé' }],
      conversation_id: null,
      usage: {},
    });
    keyboard.rewrite.mockResolvedValue({ text: 'x', usage: {} });
  });

  describe('POST /v1/keyboard/reply', () => {
    it('passes lang, trimmed contact_label and remember through to the service', async () => {
      const res = await http().post('/v1/keyboard/reply').set(bearer()).send({
        platform: 'zalo',
        contact_label: '  Linh  ',
        lang: 'vi',
        remember: false,
        conversation,
        tone: 'Friendly',
      });

      expect(res.status).toBe(200);
      expect(res.body).toHaveProperty('conversation_id', null);
      const [user, dto] = keyboard.reply.mock.calls[0];
      expect(user.sub).toBe('user-1');
      expect(dto).toMatchObject({
        platform: 'zalo',
        contact_label: 'Linh',
        lang: 'vi',
        remember: false,
      });
    });

    it.each([
      ['unsupported lang', { lang: 'fr' }],
      ['over-long contact_label', { contact_label: 'x'.repeat(129) }],
      ['blank contact_label', { contact_label: '   ' }],
      ['string remember', { remember: 'false' }],
      ['unknown field', { remember_forever: true }],
    ])('400s with the error envelope on %s', async (_label, extra) => {
      const res = await http()
        .post('/v1/keyboard/reply')
        .set(bearer())
        .send({ conversation, tone: 'Friendly', ...extra });

      expect(res.status).toBe(400);
      expect(res.body.error).toEqual(
        expect.objectContaining({ code: expect.any(String) }),
      );
      expect(keyboard.reply).not.toHaveBeenCalled();
    });

    it('401s without a token', async () => {
      const res = await http()
        .post('/v1/keyboard/reply')
        .send({ conversation, tone: 'Friendly', lang: 'en' });
      expect(res.status).toBe(401);
    });
  });

  describe('POST /v1/keyboard/rewrite', () => {
    it('accepts lang and passes it through', async () => {
      const res = await http()
        .post('/v1/keyboard/rewrite')
        .set(bearer())
        .send({ text: 'ok see u', tone: 'Professional', lang: 'en' });
      expect(res.status).toBe(200);
      expect(keyboard.rewrite.mock.calls[0][1]).toMatchObject({ lang: 'en' });
    });

    it('400s on an unsupported lang', async () => {
      const res = await http()
        .post('/v1/keyboard/rewrite')
        .set(bearer())
        .send({ text: 'ok', tone: 'Professional', lang: 'jp' });
      expect(res.status).toBe(400);
    });
  });

  describe('PATCH /me/settings', () => {
    it('updates memory_opt_in for the caller', async () => {
      auth.updateSettings.mockResolvedValue({
        settings: { memory_opt_in: true },
      });
      const res = await http()
        .patch('/me/settings')
        .set(bearer())
        .send({ memory_opt_in: true });

      expect(res.status).toBe(200);
      expect(res.body).toEqual({ settings: { memory_opt_in: true } });
      expect(auth.updateSettings).toHaveBeenCalledWith('user-1', {
        memory_opt_in: true,
      });
    });

    it.each([
      ['missing field', {}],
      ['string boolean', { memory_opt_in: 'true' }],
      ['unknown field', { memory_opt_in: true, tier: 'pro' }],
    ])('400s on %s', async (_label, body) => {
      const res = await http().patch('/me/settings').set(bearer()).send(body);
      expect(res.status).toBe(400);
      expect(auth.updateSettings).not.toHaveBeenCalled();
    });

    it('401s without a token', async () => {
      const res = await http()
        .patch('/me/settings')
        .send({ memory_opt_in: true });
      expect(res.status).toBe(401);
    });
  });

  describe('DELETE /conversations/:id', () => {
    const id = '7b0c4a3e-2f4e-4b8a-9a52-0d6f1f4a9c11';

    it('204s and scopes the delete to the caller', async () => {
      conversations.remove.mockResolvedValue(undefined);
      const res = await http().delete(`/conversations/${id}`).set(bearer());
      expect(res.status).toBe(204);
      expect(conversations.remove).toHaveBeenCalledWith('user-1', id);
    });

    it('400s on a non-UUID id without touching the service', async () => {
      const res = await http()
        .delete('/conversations/not-a-uuid')
        .set(bearer());
      expect(res.status).toBe(400);
      expect(conversations.remove).not.toHaveBeenCalled();
    });

    it('401s without a token', async () => {
      const res = await http().delete(`/conversations/${id}`);
      expect(res.status).toBe(401);
    });
  });
});
