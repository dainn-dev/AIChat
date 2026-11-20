import { MergeableMessage, messagesToAppend } from './conversation-merge';

const them = (content: string): MergeableMessage => ({
  sender: 'them',
  content,
});
const me = (content: string): MergeableMessage => ({ sender: 'me', content });

describe('messagesToAppend (Android Phase 0 capture merge)', () => {
  it('appends everything when nothing is stored yet', () => {
    const incoming = [them('hi'), me('hey')];
    expect(messagesToAppend([], incoming)).toEqual(incoming);
  });

  it('returns [] for an empty capture', () => {
    expect(messagesToAppend([them('hi')], [])).toEqual([]);
  });

  it('appends only the remainder after a partial overlap', () => {
    const stored = [them('a'), me('b'), them('c')];
    const incoming = [me('b'), them('c'), me('d'), them('e')];
    expect(messagesToAppend(stored, incoming)).toEqual([me('d'), them('e')]);
  });

  it('appends nothing when the capture is identical to the stored tail', () => {
    const stored = [them('a'), me('b'), them('c')];
    expect(messagesToAppend(stored, [me('b'), them('c')])).toEqual([]);
    expect(messagesToAppend(stored, stored)).toEqual([]);
  });

  it('appends all when there is no overlap', () => {
    const stored = [them('a'), me('b')];
    const incoming = [them('x'), me('y')];
    expect(messagesToAppend(stored, incoming)).toEqual(incoming);
  });

  it('prefers the LONGEST overlap when repeated messages allow several', () => {
    // stored ends with "ok, ok"; incoming starts "ok, ok, ok, new".
    // Longest suffix==prefix is k=2 → append "ok, new" (not k=1 → "ok, ok, new").
    const stored = [them('hi'), me('ok'), me('ok')];
    const incoming = [me('ok'), me('ok'), me('ok'), them('new')];
    expect(messagesToAppend(stored, incoming)).toEqual([me('ok'), them('new')]);
  });

  it('treats the same text from a different sender as a different message', () => {
    const stored = [them('lol')];
    const incoming = [me('lol'), them('next')];
    expect(messagesToAppend(stored, incoming)).toEqual(incoming);
  });

  it('is case- and whitespace-sensitive (callers normalize first)', () => {
    expect(messagesToAppend([them('Hi')], [them('hi')])).toEqual([them('hi')]);
  });

  it('works when stored is longer than incoming (only the tail matters)', () => {
    const stored = [them('1'), me('2'), them('3'), me('4'), them('5')];
    expect(messagesToAppend(stored, [them('5'), me('6')])).toEqual([me('6')]);
  });

  it('does not match an overlap that is not at the end of stored', () => {
    // "b" appears in stored but is not its suffix → no overlap → append all.
    const stored = [them('a'), me('b'), them('c')];
    const incoming = [me('b'), me('z')];
    expect(messagesToAppend(stored, incoming)).toEqual(incoming);
  });

  it('does not mutate its inputs and returns a new array', () => {
    const stored = Object.freeze([them('a')]);
    const incoming = Object.freeze([them('x')]);
    const out = messagesToAppend(stored, incoming);
    expect(out).toEqual([them('x')]);
    expect(out).not.toBe(incoming);
  });

  it('preserves extra fields on incoming items', () => {
    const incoming = [{ sender: 'me', content: 'n', extra: 1 }];
    expect(messagesToAppend([], incoming)[0].extra).toBe(1);
  });
});
