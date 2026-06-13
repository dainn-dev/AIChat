import { LlmConfig } from '../../config/configuration';
import { AiRequestType } from '../enums/source.enum';
import { LlmCompletionRequest } from './llm-provider.interface';
import { OpenAiProvider } from './openai-llm.provider';

const baseCfg: LlmConfig = {
  provider: 'openai',
  apiKey: 'sk-test',
  requestTimeoutMs: 30000,
  maxRepairRetries: 1,
};

function okResponse(content: string) {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    json: async () => ({
      choices: [{ message: { content } }],
      usage: { prompt_tokens: 11, completion_tokens: 7 },
    }),
  } as unknown as Response;
}

const replyRequest: LlmCompletionRequest = {
  prompt: { system: 'sys', user: 'usr' },
  type: AiRequestType.Reply,
  expectJson: true,
};

describe('OpenAiProvider', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('throws if constructed without an API key', () => {
    expect(() => new OpenAiProvider({ ...baseCfg, apiKey: undefined })).toThrow(
      /LLM_API_KEY/,
    );
  });

  it('defaults to the public OpenAI endpoint when no baseUrl is set', async () => {
    const fetchMock = jest
      .spyOn(global, 'fetch')
      .mockResolvedValue(okResponse('{"replies":[]}'));

    const provider = new OpenAiProvider(baseCfg);
    const result = await provider.complete(replyRequest);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('https://api.openai.com/v1/chat/completions');
    expect((init?.headers as Record<string, string>).authorization).toBe(
      'Bearer sk-test',
    );
    expect(result.provider).toBe('openai');
    expect(result.model).toBe('gpt-4o-mini');
    expect(result.text).toBe('{"replies":[]}');
    expect(result.tokensIn).toBe(11);
    expect(result.tokensOut).toBe(7);
  });

  it('targets the proxy/gateway base when LLM_BASE_URL is set', async () => {
    const fetchMock = jest
      .spyOn(global, 'fetch')
      .mockResolvedValue(okResponse('ok'));

    const provider = new OpenAiProvider({
      ...baseCfg,
      baseUrl: 'https://proxy.internal/v1/',
    });
    await provider.complete({ ...replyRequest, expectJson: false });

    expect(fetchMock.mock.calls[0][0]).toBe(
      'https://proxy.internal/v1/chat/completions',
    );
  });

  it('requests json_object format only when JSON is expected', async () => {
    const fetchMock = jest
      .spyOn(global, 'fetch')
      .mockResolvedValue(okResponse('{}'));

    const provider = new OpenAiProvider(baseCfg);
    await provider.complete(replyRequest);
    let body = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(body.response_format).toEqual({ type: 'json_object' });

    await provider.complete({
      prompt: { system: 's', user: 'u' },
      type: AiRequestType.Rewrite,
    });
    body = JSON.parse(fetchMock.mock.calls[1][1]?.body as string);
    expect(body.response_format).toBeUndefined();
  });

  it('routes Analysis to the analysis model and Reply/Rewrite to the reply model', async () => {
    const fetchMock = jest
      .spyOn(global, 'fetch')
      .mockResolvedValue(okResponse('{}'));

    const provider = new OpenAiProvider({
      ...baseCfg,
      replyModel: 'gpt-reply',
      analysisModel: 'gpt-analysis',
    });

    await provider.complete(replyRequest);
    await provider.complete({
      prompt: { system: 's', user: 'u' },
      type: AiRequestType.Analysis,
      expectJson: true,
    });

    expect(JSON.parse(fetchMock.mock.calls[0][1]?.body as string).model).toBe(
      'gpt-reply',
    );
    expect(JSON.parse(fetchMock.mock.calls[1][1]?.body as string).model).toBe(
      'gpt-analysis',
    );
  });

  it('throws on a non-OK upstream response so the pipeline records an error', async () => {
    jest.spyOn(global, 'fetch').mockResolvedValue({
      ok: false,
      status: 429,
      statusText: 'Too Many Requests',
      text: async () => 'rate limited',
    } as unknown as Response);

    const provider = new OpenAiProvider(baseCfg);
    await expect(provider.complete(replyRequest)).rejects.toThrow(/429/);
  });
});
