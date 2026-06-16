import { EmbeddingConfig } from '../../config/configuration';
import { OpenAiEmbeddingProvider } from './openai-embedding.provider';

const baseCfg: EmbeddingConfig = {
  provider: 'openai',
  model: 'text-embedding-3-small',
  dimensions: 4,
  apiKey: 'sk-test',
  requestTimeoutMs: 30000,
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

describe('OpenAiEmbeddingProvider', () => {
  afterEach(() => jest.restoreAllMocks());

  it('throws when constructed without an API key', () => {
    expect(
      () => new OpenAiEmbeddingProvider({ ...baseCfg, apiKey: undefined }),
    ).toThrow(/LLM_API_KEY/);
  });

  it('calls the default OpenAI embeddings endpoint, pins N, and maps the vector', async () => {
    const fetchMock = mockFetchOnce({
      data: [{ embedding: [0.1, 0.2, 0.3, 0.4] }],
      model: 'text-embedding-3-small',
    });

    const provider = new OpenAiEmbeddingProvider(baseCfg);
    const result = await provider.embed('hello');

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('https://api.openai.com/v1/embeddings');
    expect(init.headers.authorization).toBe('Bearer sk-test');
    const body = JSON.parse(init.body as string);
    expect(body).toMatchObject({
      model: 'text-embedding-3-small',
      input: 'hello',
      dimensions: 4,
    });
    expect(result).toEqual({
      vector: [0.1, 0.2, 0.3, 0.4],
      model: 'text-embedding-3-small',
      dimensions: 4,
    });
  });

  it('routes through LLM_BASE_URL when set (proxy/gateway), stripping trailing slash', async () => {
    const fetchMock = mockFetchOnce({
      data: [{ embedding: [1, 0, 0, 0] }],
    });
    const provider = new OpenAiEmbeddingProvider({
      ...baseCfg,
      baseUrl: 'https://proxy.local/v1/',
    });
    await provider.embed('x');
    expect(fetchMock.mock.calls[0][0]).toBe(
      'https://proxy.local/v1/embeddings',
    );
  });

  it('throws on a non-OK response (caller stores NULL for retry)', async () => {
    mockFetchOnce({ error: 'nope' }, false, 500);
    const provider = new OpenAiEmbeddingProvider(baseCfg);
    await expect(provider.embed('x')).rejects.toThrow(/failed \(500/);
  });

  it('throws when the returned vector width does not match N', async () => {
    mockFetchOnce({ data: [{ embedding: [0.1, 0.2] }] });
    const provider = new OpenAiEmbeddingProvider(baseCfg);
    await expect(provider.embed('x')).rejects.toThrow(
      /2-dim vector; expected 4/,
    );
  });

  it('throws when the response carries no vector', async () => {
    mockFetchOnce({ data: [] });
    const provider = new OpenAiEmbeddingProvider(baseCfg);
    await expect(provider.embed('x')).rejects.toThrow(/no vector/);
  });
});
