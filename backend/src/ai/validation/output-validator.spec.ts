import { OutputValidator } from './output-validator';
import { Tone } from '../enums/tone.enum';

describe('OutputValidator', () => {
  const v = new OutputValidator();

  describe('parseJsonLoose', () => {
    it('parses clean JSON', () => {
      expect(v.parseJsonLoose('{"a":1}')).toEqual({ a: 1 });
    });

    it('strips markdown code fences', () => {
      const raw = 'Sure!\n```json\n{"a":2}\n```\nHope that helps.';
      expect(v.parseJsonLoose(raw)).toEqual({ a: 2 });
    });

    it('extracts the first balanced object from surrounding prose', () => {
      const raw = 'Here you go: {"summary":"hi","interest_score":50} cheers';
      expect(v.parseJsonLoose(raw)).toEqual({
        summary: 'hi',
        interest_score: 50,
      });
    });

    it('returns null for unparseable garbage', () => {
      expect(v.parseJsonLoose('not json at all')).toBeNull();
      expect(v.parseJsonLoose('')).toBeNull();
    });
  });

  describe('clampScore (FR-N3 / AC-N2)', () => {
    it.each([
      [150, 100],
      [-20, 0],
      [42.6, 43],
      ['73', 73],
      ['nonsense', 0],
      [null, 0],
      [undefined, 0],
      [NaN, 0],
    ])('clamps %p -> %p', (input, expected) => {
      expect(v.clampScore(input)).toBe(expected);
    });
  });

  describe('toAnalyzeResponse', () => {
    it('keeps valid fields and clamps the score', () => {
      const res = v.toAnalyzeResponse({
        summary: 'A friendly chat.',
        interest_score: 250,
        suggested_replies: [{ tone: 'Flirty', text: 'hey you' }],
        red_flags: [{ code: 'slow', message: 'slow replies' }],
      });
      expect(res.summary).toBe('A friendly chat.');
      expect(res.interest_score).toBe(100);
      expect(res.suggested_replies).toHaveLength(1);
      expect(res.suggested_replies[0].tone).toBe(Tone.Flirty);
      expect(res.red_flags).toHaveLength(1);
      expect(res.degraded).toBe(false);
    });

    it('degrades safely on null (unparseable) without throwing', () => {
      const res = v.toAnalyzeResponse(null);
      expect(res.degraded).toBe(true);
      expect(res.interest_score).toBe(0);
      expect(res.summary.length).toBeGreaterThan(0);
      expect(res.suggested_replies.length).toBeGreaterThanOrEqual(1);
      expect(res.red_flags).toEqual([]);
    });

    it('guarantees a non-empty summary and >=1 suggested reply (AC-N1)', () => {
      const res = v.toAnalyzeResponse({ interest_score: 30 });
      expect(res.summary.length).toBeGreaterThan(0);
      expect(res.suggested_replies.length).toBeGreaterThanOrEqual(1);
      expect(res.degraded).toBe(true);
    });

    it('normalizes string red_flags and unknown tones', () => {
      const res = v.toAnalyzeResponse({
        summary: 'ok',
        interest_score: 10,
        suggested_replies: [{ tone: 'NotARealTone', text: 'hi' }],
        red_flags: ['ghosting risk'],
      });
      expect(res.suggested_replies[0].tone).toBe(Tone.Friendly);
      expect(res.red_flags[0]).toEqual({
        code: 'flag',
        message: 'ghosting risk',
      });
    });
  });

  describe('toReplyCandidates', () => {
    it('extracts replies and tags them with the requested tone (AC-R1)', () => {
      const parsed = { replies: [{ text: 'a' }, { text: 'b' }, { text: 'c' }] };
      const out = v.toReplyCandidates(parsed, Tone.Flirty, 2);
      expect(out).toHaveLength(2);
      expect(out.every((r) => r.tone === Tone.Flirty)).toBe(true);
      expect(out.map((r) => r.text)).toEqual(['a', 'b']);
    });

    it('handles a bare string array', () => {
      const out = v.toReplyCandidates(['x', 'y'], Tone.Funny, 5);
      expect(out.map((r) => r.text)).toEqual(['x', 'y']);
    });

    it('returns [] for unusable output so the caller can degrade', () => {
      expect(v.toReplyCandidates(null, Tone.Mature, 3)).toEqual([]);
      expect(v.toReplyCandidates({ replies: 'nope' }, Tone.Mature, 3)).toEqual(
        [],
      );
    });
  });
});
