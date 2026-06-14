import { EmbeddingService } from './embedding.service';
import {
  EmbeddingProvider,
  EmbeddingResult,
} from './embedding-provider.interface';

function fakeProvider(
  overrides: Partial<EmbeddingProvider> = {},
): EmbeddingProvider {
  return {
    name: 'fake',
    model: 'fake-model-v1',
    embed: jest.fn(
      async (): Promise<EmbeddingResult> => ({
        vector: [1, 0, 0],
        model: 'fake-model-v1',
        dimensions: 3,
      }),
    ),
    ...overrides,
  };
}

describe('EmbeddingService', () => {
  it('exposes the provider target model for backfill staleness checks', () => {
    const service = new EmbeddingService(fakeProvider());
    expect(service.model).toBe('fake-model-v1');
  });

  it('embed() returns the provider vector and normalizes whitespace (AC-E1)', async () => {
    const provider = fakeProvider();
    const service = new EmbeddingService(provider);

    const result = await service.embed('  multi   line\n text ');
    expect(result.vector).toEqual([1, 0, 0]);
    expect(provider.embed).toHaveBeenCalledWith('multi line text');
  });

  it('embed() propagates provider errors', async () => {
    const provider = fakeProvider({
      embed: jest.fn().mockRejectedValue(new Error('boom')),
    });
    const service = new EmbeddingService(provider);
    await expect(service.embed('x')).rejects.toThrow('boom');
  });

  it('embedOrNull() returns null on provider failure instead of throwing (AC-E2)', async () => {
    const provider = fakeProvider({
      embed: jest.fn().mockRejectedValue(new Error('provider down')),
    });
    const service = new EmbeddingService(provider);
    await expect(service.embedOrNull('keep me')).resolves.toBeNull();
  });

  it('embedOrNull() returns null for empty/whitespace content without calling the provider', async () => {
    const provider = fakeProvider();
    const service = new EmbeddingService(provider);
    await expect(service.embedOrNull('   ')).resolves.toBeNull();
    expect(provider.embed).not.toHaveBeenCalled();
  });

  it('embedOrNull() returns the embedding on success', async () => {
    const service = new EmbeddingService(fakeProvider());
    const result = await service.embedOrNull('hi');
    expect(result).toMatchObject({ vector: [1, 0, 0], model: 'fake-model-v1' });
  });
});
