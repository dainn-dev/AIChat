import { applyFactScopePolicy } from './fact-scope-policy';
import { ExtractedFact } from './extraction/extraction-provider.interface';

const fact = (over: Partial<ExtractedFact>): ExtractedFact => ({
  kind: 'interest',
  content: 'Loves hiking',
  confidence: 0.9,
  scope: 'user',
  ...over,
});

describe('applyFactScopePolicy (security M3)', () => {
  it('accepts a global user fact stated by "me"', () => {
    expect(applyFactScopePolicy(fact({ fromSender: 'me' }), 'Linh')).toEqual(
      expect.objectContaining({ scope: 'user', requireReview: false }),
    );
  });

  it('re-scopes a "user" fact attributed to "them" to the contact', () => {
    expect(applyFactScopePolicy(fact({ fromSender: 'them' }), 'Linh')).toEqual(
      expect.objectContaining({ scope: 'contact', requireReview: false }),
    );
  });

  it('forces review for a global fact with unknown attribution', () => {
    expect(applyFactScopePolicy(fact({}), 'Linh')).toEqual(
      expect.objectContaining({ scope: 'user', requireReview: true }),
    );
  });

  it('forces review when there is no contact label and the fact is not from "me" (would be global)', () => {
    expect(
      applyFactScopePolicy(fact({ fromSender: 'them' }), undefined),
    ).toEqual(
      expect.objectContaining({ scope: 'contact', requireReview: true }),
    );
    expect(
      applyFactScopePolicy(
        fact({ scope: 'contact', fromSender: 'them' }),
        undefined,
      ).requireReview,
    ).toBe(true);
  });

  it('leaves contact-scoped facts with a contact label alone', () => {
    expect(
      applyFactScopePolicy(
        fact({ scope: 'contact', fromSender: 'them' }),
        'Linh',
      ),
    ).toEqual(
      expect.objectContaining({ scope: 'contact', requireReview: false }),
    );
  });

  it('does not mutate its input', () => {
    const input = Object.freeze(fact({ fromSender: 'them' }));
    applyFactScopePolicy(input, 'Linh');
    expect(input.scope).toBe('user');
  });
});
