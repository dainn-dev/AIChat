import { ConfigService } from '@nestjs/config';
import { DataSource } from 'typeorm';
import { MemoryExtractionService } from './memory-extraction.service';
import { MemoryWriterService } from './memory-writer.service';
import { SensitiveDataFilter } from './privacy/sensitive-data.filter';
import { MemoryExtractionProvider } from './extraction/extraction-provider.interface';

/**
 * Android Phase 0 — extraction honours the keyboard opt-in and drops results
 * when the writer's in-transaction guard aborts (conversation erased mid-run).
 */
function build(conv: { source: string; memory_opt_in: boolean } | null) {
  const query = jest.fn(async (sql: string) => {
    if (/FROM conversations c JOIN users u/.test(sql)) {
      return conv
        ? [{ user_id: 'user-1', contact_label: 'Linh', ...conv }]
        : [];
    }
    if (/count\(\*\)/.test(sql)) return [{ count: '0' }];
    if (/SELECT sender, content FROM messages/.test(sql)) {
      return [{ sender: 'them', content: 'I love hiking' }];
    }
    return [];
  });
  const extractor: MemoryExtractionProvider = {
    name: 'stub',
    extract: jest.fn().mockResolvedValue([
      {
        kind: 'interest',
        content: 'Loves hiking',
        confidence: 0.9,
        scope: 'contact',
      },
    ]),
  } as unknown as MemoryExtractionProvider;
  const writer = { writeFacts: jest.fn() };
  const service = new MemoryExtractionService(
    { query } as unknown as DataSource,
    extractor,
    writer as unknown as MemoryWriterService,
    new SensitiveDataFilter(),
    { get: () => ({ extractionDailyBudget: 200 }) } as unknown as ConfigService,
  );
  return { service, query, extractor, writer };
}

describe('MemoryExtractionService (Android Phase 0)', () => {
  it('skips a keyboard conversation whose owner is not opted in, before any LLM call', async () => {
    const { service, extractor, writer } = build({
      source: 'keyboard',
      memory_opt_in: false,
    });
    const res = await service.extractForConversation('conv-1');
    expect(res.status).toBe('opted-out');
    expect(extractor.extract).not.toHaveBeenCalled();
    expect(writer.writeFacts).not.toHaveBeenCalled();
  });

  it('extracts non-keyboard conversations regardless of the opt-in (screenshots stay ungated)', async () => {
    const { service, writer } = build({ source: 'ocr', memory_opt_in: false });
    writer.writeFacts.mockResolvedValue({ written: 1, superseded: 0 });
    const res = await service.extractForConversation('conv-1');
    expect(res.status).toBe('extracted');
    expect(writer.writeFacts).toHaveBeenCalledWith(
      expect.objectContaining({
        conversationId: 'conv-1',
        sourceRef: 'conversation:conv-1',
      }),
    );
  });

  it('drops results and skips the audit row when the writer aborts', async () => {
    const { service, writer, query } = build({
      source: 'keyboard',
      memory_opt_in: true,
    });
    writer.writeFacts.mockResolvedValue({
      written: 0,
      superseded: 0,
      aborted: 'conversation-gone',
    });
    const res = await service.extractForConversation('conv-1');
    expect(res).toEqual({
      status: 'aborted',
      factsExtracted: 1,
      factsWritten: 0,
    });
    expect(
      query.mock.calls.some(([sql]) =>
        /INSERT INTO memory_extractions/.test(sql as string),
      ),
    ).toBe(false);
  });

  it('returns no-conversation when it was already erased', async () => {
    const { service, extractor } = build(null);
    const res = await service.extractForConversation('conv-1');
    expect(res.status).toBe('no-conversation');
    expect(extractor.extract).not.toHaveBeenCalled();
  });
});
