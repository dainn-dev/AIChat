import { plainToInstance } from 'class-transformer';
import { validate } from 'class-validator';
import { UpdateSettingsDto } from './update-settings.dto';

const errorsFor = async (body: object) =>
  (
    await validate(plainToInstance(UpdateSettingsDto, body), {
      whitelist: true,
      forbidNonWhitelisted: true,
    })
  ).map((e) => e.property);

describe('UpdateSettingsDto', () => {
  it.each([
    [{ memory_opt_in: true }],
    [{ training_opt_in: false }],
    [{ memory_opt_in: false, training_opt_in: true }],
  ])('accepts %j', async (body) => {
    expect(await errorsFor(body)).toEqual([]);
  });

  it('rejects an empty body', async () => {
    expect(await errorsFor({})).toEqual(['memory_opt_in', 'training_opt_in']);
  });

  it.each([
    [{ training_opt_in: 'true' }, ['training_opt_in']],
    [{ memory_opt_in: true, training_opt_in: null }, ['training_opt_in']],
    [{ memory_opt_in: 1, training_opt_in: true }, ['memory_opt_in']],
  ])('rejects non-booleans in %j', async (body, fields) => {
    expect(await errorsFor(body)).toEqual(fields);
  });
});
