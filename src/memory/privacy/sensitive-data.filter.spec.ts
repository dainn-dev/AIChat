import { SensitiveDataFilter } from './sensitive-data.filter';

describe('SensitiveDataFilter', () => {
  const filter = new SensitiveDataFilter();

  it('drops health-category facts entirely', () => {
    const res = filter.scan('Has a cancer diagnosis');
    expect(res.dropped).toBe(true);
    expect(res.droppedCategory).toBe('health');
  });

  it('drops financial-category facts', () => {
    expect(filter.scan('Salary is around 90k').dropped).toBe(true);
    expect(filter.scan('Shared their bank account details').dropped).toBe(true);
  });

  it('drops minor-related facts', () => {
    expect(filter.scan('Is a minor').dropped).toBe(true);
    expect(filter.scan('She is 15 years old').dropped).toBe(true);
  });

  it('redacts inline phone numbers and emails', () => {
    const res = filter.scan('Reach them at 555-123-4567 or alex@example.com');
    expect(res.dropped).toBe(false);
    expect(res.redacted).toBe(true);
    expect(res.content).toContain('[redacted-phone]');
    expect(res.content).toContain('[redacted-email]');
    expect(res.content).not.toContain('555-123-4567');
    expect(res.content).not.toContain('alex@example.com');
  });

  it('redacts street addresses and card-like numbers', () => {
    const res = filter.scan('Lives at 221 Baker Street; card 4111111111111111');
    expect(res.content).toContain('[redacted-address]');
    expect(res.content).toContain('[redacted-card]');
  });

  it('leaves an ordinary fact untouched', () => {
    const res = filter.scan('Works as a chef and enjoys hiking');
    expect(res).toMatchObject({ dropped: false, redacted: false });
    expect(res.content).toBe('Works as a chef and enjoys hiking');
  });
});
