import { MEMORY_KINDS } from './memory.dto';

describe('memory dashboard kinds', () => {
  it('includes communication style memories in the supported kinds', () => {
    expect(MEMORY_KINDS).toContain('style');
  });
});
