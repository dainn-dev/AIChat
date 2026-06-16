import { StubMemoryExtractionProvider } from './stub-extraction.provider';

describe('StubMemoryExtractionProvider', () => {
  const provider = new StubMemoryExtractionProvider();

  it('AC-M1: extracts job and interest facts from a conversation', async () => {
    const facts = await provider.extract({
      contactLabel: 'Alex',
      messages: [
        { sender: 'them', content: 'I work as a chef at a bistro downtown' },
        { sender: 'them', content: 'I love hiking on the weekends' },
        { sender: 'me', content: 'nice, what are you up to later' },
      ],
    });

    const job = facts.find((f) => f.kind === 'job');
    const interest = facts.find((f) => f.kind === 'interest');
    expect(job).toBeDefined();
    expect(job!.scope).toBe('contact');
    expect(job!.confidence).toBeGreaterThanOrEqual(0.7);
    expect(job!.content.toLowerCase()).toContain('chef');
    expect(interest).toBeDefined();
    expect(interest!.content.toLowerCase()).toContain('hiking');
  });

  it('attributes "me" statements to the user scope (global facts)', async () => {
    const facts = await provider.extract({
      messages: [{ sender: 'me', content: 'I live in Berlin near the park' }],
    });
    expect(facts).toHaveLength(1);
    expect(facts[0]).toMatchObject({ kind: 'fact', scope: 'user' });
    expect(facts[0].content.toLowerCase()).toContain('berlin');
  });

  it('routes a weak statement to low confidence (→ pending_review)', async () => {
    const facts = await provider.extract({
      messages: [{ sender: 'them', content: 'I have a cat named Mochi' }],
    });
    expect(facts[0].kind).toBe('fact');
    expect(facts[0].confidence).toBeLessThan(0.7);
  });

  it('AC-M3: thin / emoji / spam input yields no facts (no hallucination)', async () => {
    const facts = await provider.extract({
      messages: [
        { sender: 'them', content: '😀😀😀' },
        { sender: 'me', content: 'ok' },
        { sender: 'them', content: 'lol' },
        { sender: 'me', content: '👍' },
      ],
    });
    expect(facts).toEqual([]);
  });

  it('is deterministic and de-duplicates repeated statements', async () => {
    const input = {
      messages: [
        { sender: 'them', content: 'I love hiking on the weekends' },
        { sender: 'them', content: 'I love hiking on the weekends' },
      ],
    };
    const a = await provider.extract(input);
    const b = await provider.extract(input);
    expect(a).toEqual(b);
    expect(a).toHaveLength(1); // dedup within one extraction
  });
});
