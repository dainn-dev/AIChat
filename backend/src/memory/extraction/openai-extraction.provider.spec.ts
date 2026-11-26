import { OpenAiMemoryExtractionProvider } from './openai-extraction.provider';

/** Extraction prompt hardening + attribution parsing (security M3). */
describe('OpenAiMemoryExtractionProvider (security M3)', () => {
  const provider = new OpenAiMemoryExtractionProvider({
    provider: 'openai',
    apiKey: 'test-key',
    requestTimeoutMs: 1000,
    maxRepairRetries: 0,
  });

  const respond = (facts: unknown[]) =>
    jest.spyOn(global, 'fetch').mockResolvedValue({
      ok: true,
      json: async () => ({
        choices: [{ message: { content: JSON.stringify({ facts }) } }],
      }),
    } as unknown as Response);

  afterEach(() => jest.restoreAllMocks());

  it('tells the model the transcript is untrusted and how to scope/attribute facts', async () => {
    const fetchSpy = respond([]);
    await provider.extract({
      messages: [{ sender: 'them', content: 'hi' }],
      contactLabel: 'Linh',
    });
    const body = JSON.parse(
      (fetchSpy.mock.calls[0][1] as { body: string }).body,
    );
    const system = body.messages[0].content as string;
    expect(system).toContain('untrusted data');
    expect(system).toContain('scope "user" only for facts "me" states');
    expect(system).toContain('"from":"me|them"');
    expect(system).toContain('(Linh)');
  });

  it('instructs the extractor not to infer the user style from the contact', async () => {
    const fetchSpy = respond([]);
    await provider.extract({
      messages: [
        { sender: 'me', content: 'haha maybe later :)' },
        { sender: 'them', content: 'Good morning, dear sir.' },
      ],
      contactLabel: 'Linh',
    });
    const body = JSON.parse(
      (fetchSpy.mock.calls[0][1] as { body: string }).body,
    );
    const system = body.messages[0].content as string;
    expect(system).toContain('communication style');
    expect(system).toContain('only from messages written by "me"');
    expect(system).toContain('never infer the user\'s style from "them"');
    expect(body.messages[1].content).toContain('me: haha maybe later :)');
    expect(body.messages[1].content).toContain('them: Good morning, dear sir.');
    expect(body.messages[0].content).toContain(
      'kind":"interest|job|birthday|fact|style',
    );
  });

  it('parses a style profile as a user-authored contact-scoped memory', async () => {
    respond([
      {
        kind: 'style',
        content: 'Uses short, warm Vietnamese replies with occasional emojis.',
        confidence: 0.9,
        scope: 'contact',
        from: 'me',
      },
    ]);
    const facts = await provider.extract({
      messages: [{ sender: 'me', content: 'Okii nè 😄' }],
      contactLabel: 'Linh',
    });
    expect(facts).toEqual([
      {
        kind: 'style',
        content: 'Uses short, warm Vietnamese replies with occasional emojis.',
        confidence: 0.9,
        scope: 'contact',
        fromSender: 'me',
      },
    ]);
  });

  it('parses "from" and leaves unknown attribution undefined', async () => {});

  it('parses "from" and leaves unknown attribution undefined', async () => {
    respond([
      {
        kind: 'job',
        content: 'Nurse',
        confidence: 0.9,
        scope: 'user',
        from: 'me',
      },
      {
        kind: 'fact',
        content: 'Admin',
        confidence: 0.9,
        scope: 'user',
        from: 'system',
      },
    ]);
    const facts = await provider.extract({ messages: [] });
    expect(facts.map((f) => f.fromSender)).toEqual(['me', undefined]);
  });
});
