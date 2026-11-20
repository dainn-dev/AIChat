import {
  containsRun,
  longestSuffixPrefixOverlap,
  MergeableMessage,
  MIN_CONTAINMENT_WINDOW,
  messagesToAppend,
} from './conversation-merge';

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

describe('messagesToAppend — scroll-up / containment heuristic', () => {
  // A 7-message stored history: 1..7 alternating senders.
  const history = [
    them('1'),
    me('2'),
    them('3'),
    me('4'),
    them('5'),
    me('6'),
    them('7'),
  ];

  it('uses a containment threshold of 3 messages', () => {
    expect(MIN_CONTAINMENT_WINDOW).toBe(3);
  });

  it('appends nothing for a window scrolled up into the middle of history', () => {
    expect(messagesToAppend(history, [me('2'), them('3'), me('4')])).toEqual(
      [],
    );
  });

  it('appends nothing for a window equal to the start of history', () => {
    expect(
      messagesToAppend(history, [them('1'), me('2'), them('3'), me('4')]),
    ).toEqual([]);
  });

  it('appends nothing when the window reaches above the stored start (older, unseen messages)', () => {
    // "0a", "0b" are older than anything stored; the window ends inside history.
    const incoming = [me('0a'), them('0b'), them('1'), me('2'), them('3')];
    expect(messagesToAppend(history, incoming)).toEqual([]);
  });

  it('still appends new messages after a normal suffix/prefix overlap', () => {
    const incoming = [me('6'), them('7'), me('8'), them('9')];
    expect(messagesToAppend(history, incoming)).toEqual([me('8'), them('9')]);
  });

  it('runs the suffix/prefix overlap BEFORE containment (review MEDIUM 1)', () => {
    // [a,b,c] also occurs earlier, but history ends with [a,b]: the overlap
    // says only `c` is new, and it must not be dropped.
    const stored = [them('a'), me('b'), them('c'), me('x'), them('a'), me('b')];
    expect(messagesToAppend(stored, [them('a'), me('b'), them('c')])).toEqual([
      them('c'),
    ]);
  });

  it('a short accidental overlap with the end of history wins over containment (documented limit)', () => {
    const stored = [them('a'), me('b'), them('c'), me('x'), them('a')];
    expect(messagesToAppend(stored, [them('a'), me('b'), them('c')])).toEqual([
      me('b'),
      them('c'),
    ]);
  });

  it('scroll-up regression: a mid-history window with no tail overlap still appends nothing', () => {
    const stored = [them('a'), me('b'), them('c'), me('d'), them('e')];
    expect(messagesToAppend(stored, [me('b'), them('c'), me('d')])).toEqual([]);
  });

  it('appends all when a partially contained window has a new ending (diverged history)', () => {
    // Starts mid-history but ends with something never seen → fallback.
    const incoming = [me('4'), them('5'), me('new')];
    expect(messagesToAppend(history, incoming)).toEqual(incoming);
  });

  it('does not apply containment to short windows (1–2 messages)', () => {
    // Two-message window contained mid-history but not at the end → appended.
    expect(messagesToAppend(history, [me('2'), them('3')])).toEqual([
      me('2'),
      them('3'),
    ]);
  });

  describe('repeated identical short messages', () => {
    const okHistory = [them('ok'), me('ok'), them('see you at 7?'), me('ok')];

    it('appends a genuinely new "ok" that follows context', () => {
      const incoming = [them('see you at 7?'), me('ok'), me('ok')];
      expect(messagesToAppend(okHistory, incoming)).toEqual([me('ok')]);
    });

    it('appends a new "ok" from the other side even though "ok" exists in history', () => {
      expect(messagesToAppend(okHistory, [me('ok'), them('ok')])).toEqual([
        them('ok'),
      ]);
    });

    it('does not drop a short new "ok, ok" exchange just because it occurs earlier', () => {
      const stored = [them('ok'), me('ok'), them('bye')];
      // 2-message window → no containment check; no overlap → appended.
      expect(messagesToAppend(stored, [them('ok'), me('ok')])).toEqual([
        them('ok'),
        me('ok'),
      ]);
    });

    it('treats a re-captured run of 3+ identical messages as already stored (documented limit)', () => {
      const stored = [me('ok'), me('ok'), me('ok'), them('later')];
      expect(messagesToAppend(stored, [me('ok'), me('ok'), me('ok')])).toEqual(
        [],
      );
    });
  });
});

describe('merge helpers', () => {
  it('longestSuffixPrefixOverlap finds the longest match', () => {
    expect(
      longestSuffixPrefixOverlap(
        [them('a'), me('b'), them('c')],
        [me('b'), them('c'), me('d')],
      ),
    ).toBe(2);
    expect(longestSuffixPrefixOverlap([], [them('a')])).toBe(0);
  });

  it('containsRun detects contiguous runs only', () => {
    const h = [them('a'), me('b'), them('c')];
    expect(containsRun(h, [me('b'), them('c')])).toBe(true);
    expect(containsRun(h, [them('a'), them('c')])).toBe(false);
    expect(containsRun(h, [])).toBe(true);
    expect(containsRun([], [them('a')])).toBe(false);
  });
});
