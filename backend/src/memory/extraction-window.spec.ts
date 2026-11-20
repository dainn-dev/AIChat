import {
  boundExtractionWindow,
  EXTRACTION_MAX_CHARS,
  EXTRACTION_MAX_MESSAGES,
} from './extraction-window';

const msg = (content: string) => ({ sender: 'them', content });

describe('boundExtractionWindow (security M4)', () => {
  it('uses 200 messages / 20k chars by default', () => {
    expect(EXTRACTION_MAX_MESSAGES).toBe(200);
    expect(EXTRACTION_MAX_CHARS).toBe(20_000);
  });

  it('keeps everything when within both caps', () => {
    const input = [msg('a'), msg('b')];
    expect(boundExtractionWindow(input)).toEqual(input);
  });

  it('keeps only the most recent N messages, in order', () => {
    const input = Array.from({ length: 10 }, (_, i) => msg(String(i)));
    expect(boundExtractionWindow(input, 3, 1000).map((m) => m.content)).toEqual(
      ['7', '8', '9'],
    );
  });

  it('drops the oldest messages to fit the char budget', () => {
    const input = [msg('aaaa'), msg('bbbb'), msg('cccc')];
    expect(boundExtractionWindow(input, 10, 9).map((m) => m.content)).toEqual([
      'bbbb',
      'cccc',
    ]);
  });

  it('truncates a single oversized newest message instead of returning nothing', () => {
    const out = boundExtractionWindow(
      [msg('old'), msg('x'.repeat(50))],
      10,
      20,
    );
    expect(out).toHaveLength(1);
    expect(out[0].content).toHaveLength(20);
  });

  it('handles empty input', () => {
    expect(boundExtractionWindow([])).toEqual([]);
  });
});
