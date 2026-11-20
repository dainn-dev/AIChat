import 'reflect-metadata';
import { plainToInstance } from 'class-transformer';
import { validate } from 'class-validator';
import {
  CONTACT_LABEL_MAX_LENGTH,
  KeyboardReplyDto,
  KeyboardRewriteDto,
} from './keyboard.dto';

/**
 * DTO validation for the Android Phase 0 keyboard params (`lang`,
 * `contact_label`). Mirrors the global ValidationPipe (transform + whitelist).
 */
async function errorsFor<T extends object>(
  cls: new () => T,
  body: Record<string, unknown>,
): Promise<string[]> {
  const instance = plainToInstance(cls, body);
  const errors = await validate(instance, {
    whitelist: true,
    forbidNonWhitelisted: true,
  });
  return errors.map((e) => e.property);
}

const conversation = [{ sender: 'them', content: 'hi' }];

describe('KeyboardReplyDto (Android Phase 0)', () => {
  it('accepts a minimal payload without the new optional fields', async () => {
    expect(
      await errorsFor(KeyboardReplyDto, { conversation, tone: 'Friendly' }),
    ).toEqual([]);
  });

  it.each(['en', 'vi'])('accepts lang=%s', async (lang) => {
    expect(
      await errorsFor(KeyboardReplyDto, {
        conversation,
        tone: 'Friendly',
        lang,
      }),
    ).toEqual([]);
  });

  it.each(['fr', 'EN', 'vietnamese', ''])('rejects lang=%p', async (lang) => {
    expect(
      await errorsFor(KeyboardReplyDto, {
        conversation,
        tone: 'Friendly',
        lang,
      }),
    ).toContain('lang');
  });

  it('accepts a contact_label up to the max length and trims it', async () => {
    const label = '  ' + 'a'.repeat(CONTACT_LABEL_MAX_LENGTH) + '  ';
    const dto = plainToInstance(KeyboardReplyDto, {
      conversation,
      tone: 'Friendly',
      contact_label: label,
    });
    expect(await validate(dto)).toEqual([]);
    expect(dto.contact_label).toBe('a'.repeat(CONTACT_LABEL_MAX_LENGTH));
  });

  it('rejects an over-long contact_label', async () => {
    expect(
      await errorsFor(KeyboardReplyDto, {
        conversation,
        tone: 'Friendly',
        contact_label: 'a'.repeat(CONTACT_LABEL_MAX_LENGTH + 1),
      }),
    ).toContain('contact_label');
  });

  it.each(['', '   ', 42])(
    'rejects a blank or non-string contact_label (%p)',
    async (contact_label) => {
      expect(
        await errorsFor(KeyboardReplyDto, {
          conversation,
          tone: 'Friendly',
          contact_label,
        }),
      ).toContain('contact_label');
    },
  );
});

describe('KeyboardRewriteDto (Android Phase 0)', () => {
  it('accepts an optional lang', async () => {
    expect(
      await errorsFor(KeyboardRewriteDto, {
        text: 'ok',
        tone: 'Funny',
        lang: 'vi',
      }),
    ).toEqual([]);
  });

  it('rejects an unsupported lang', async () => {
    expect(
      await errorsFor(KeyboardRewriteDto, {
        text: 'ok',
        tone: 'Funny',
        lang: 'de',
      }),
    ).toContain('lang');
  });

  it('rejects contact_label on rewrite (not part of the contract)', async () => {
    expect(
      await errorsFor(KeyboardRewriteDto, {
        text: 'ok',
        tone: 'Funny',
        contact_label: 'Linh',
      }),
    ).toContain('contact_label');
  });
});
