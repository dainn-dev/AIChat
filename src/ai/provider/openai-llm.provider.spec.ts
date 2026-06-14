import { LlmConfig } from '../../config/configuration';
import { AiRequestType } from '../enums/source.enum';
import { OpenAiProvider } from './openai-llm.provider';

const baseCfg: LlmConfig = {
  provider: 'openai',
  apiKey: 'sk-test',
  requestTimeoutMs: 30000,
  maxRepairRetries: 1,
};

function mockFetchOnce(payload: unknown, ok = true, status = 200) {
  const fetchMock = jest.fn().mockResolvedValue({
    ok,
    status,
    statusText: ok ? 'OK' : 'Error',
    json: async () => payload,
    text: async () => JSON.stringify(payload),
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  return fetchMock;
}

const reply = (type = AiRequestType.Reply) => ({
  prompt: { system: 'sys', user: 'usr' },
  type,
  expectJson: type !== AiRequestType.Rewrite,
});

describe('OpenAiProvider', () => {
  afterEach(() => jest.restoreAllMocks());

  it('throws when constructed without an API key', () => {
    expect(() => new OpenAiProvider({ ...baseCfg, apiKey: undefined })).toThrow(
      /LLM_API_KEY/,
    );
  });

  it('calls the default OpenAI endpoint and maps the response + usage', async () => {
    const fetchMock = mockFetchOnce({
      choices: [{ message: { content: '{"ok":true}' } }],
      usage: { prompt_tokens: 11, completion_tokens: 7 },
      model: 'gpt-4o-mini',
    });

    const provider = new OpenAiProvider(baseCfg);
    const result = await provider.complete(reply());

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('https://api.openai.com/v1/chat/completions');
    expect(init.headers.authorization).toBe('Bearer sk-test');
    expect(result).toMatchObject({
      text: '{"ok":true}',
      provider: 'openai',
      model: 'gpt-4o-mini',
      tokensIn: 11,
      tokensOut: 7,
    });
  });

  it('points the client at LLM_BASE_URL when set (proxy/gateway)', async () => {
    const fetchMock = mockFetchOnce({
      choices: [{ message: { content: 'hi' } }],
    });

    const provider = new OpenAiProvider({
      ...baseCfg,
      baseUrl: 'https://proxy.example.com/v1/',
    });
    await provider.complete(reply(AiRequestType.Rewrite));

    expect(fetchMock.mock.calls[0][0]).toBe(
      'https://proxy.example.com/v1/chat/completions',
    );
    // Rewrite is free-text → no strict JSON mode requested.
    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body.response_format).toBeUndefined();
  });

  it('requests JSON mode and routes analysis to the analysis model', async () => {
    const fetchMock = mockFetchOnce({
      choices: [{ message: { content: '{}' } }],
    });

    const provider = new OpenAiProvider({
      ...baseCfg,
      replyModel: 'gpt-reply',
      analysisModel: 'gpt-analysis',
    });
    await provider.complete(reply(AiRequestType.Analysis));

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body.model).toBe('gpt-analysis');
    expect(body.response_format).toEqual({ type: 'json_object' });
  });

  it('throws a descriptive error on a non-2xx response', async () => {
    mockFetchOnce({ error: 'bad key' }, false, 401);
    const provider = new OpenAiProvider(baseCfg);
    await expect(provider.complete(reply())).rejects.toThrow(/401/);
  });
});
