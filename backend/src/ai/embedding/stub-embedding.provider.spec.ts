import { ConfigService } from '@nestjs/config';
import { StubEmbeddingProvider } from './stub-embedding.provider';

function providerWithDims(dimensions: number): StubEmbeddingProvider {
  const config = {
    getOrThrow: () => ({ dimensions }),
  } as unknown as ConfigService;
  return new StubEmbeddingProvider(config);
}

describe('StubEmbeddingProvider', () => {
  it('produces a unit-normalized vector of the configured dimension', async () => {
    const provider = providerWithDims(1536);
    const { vector, model, dimensions } = await provider.embed('hello world');

    expect(vector).toHaveLength(1536);
    expect(dimensions).toBe(1536);
    expect(model).toBe('stub-embedding-v1');
    const norm = Math.sqrt(vector.reduce((s, v) => s + v * v, 0));
    expect(norm).toBeCloseTo(1, 5);
  });

  it('is deterministic — same text embeds to the same vector (idempotent backfill)', async () => {
    const provider = providerWithDims(8);
    const a = await provider.embed('same input');
    const b = await provider.embed('same input');
    expect(a.vector).toEqual(b.vector);
  });

  it('different text yields a different vector', async () => {
    const provider = providerWithDims(8);
    const a = await provider.embed('alpha');
    const b = await provider.embed('beta');
    expect(a.vector).not.toEqual(b.vector);
  });
});
