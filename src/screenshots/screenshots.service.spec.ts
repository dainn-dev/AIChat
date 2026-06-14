import { NotFoundException } from '@nestjs/common';
import { DataSource, EntityManager } from 'typeorm';
import { AiPipelineService } from '../ai/pipeline/ai-pipeline.service';
import { MessageSender } from '../ai/enums/source.enum';
import { Tier } from '../common/tiers';
import { QuotaExceededException } from '../usage/exceptions/quota-exceeded.exception';
import { UsageMetric } from '../usage/usage.constants';
import { UsageService } from '../usage/usage.service';
import { CreateScreenshotDto } from './dto/create-screenshot.dto';
import { OcrFailedException } from './exceptions/ocr-failed.exception';
import { ScreenshotsService } from './screenshots.service';

const USER = 'user-1';
const USAGE = {
  replies_used: 0,
  replies_limit: 20,
  screenshots_used: 1,
  screenshots_limit: 5,
};

describe('ScreenshotsService', () => {
  let service: ScreenshotsService;
  let usage: jest.Mocked<
    Pick<UsageService, 'runWithQuota' | 'getUsageSummary'>
  >;
  let pipeline: jest.Mocked<Pick<AiPipelineService, 'analyze'>>;
  let dataSourceQuery: jest.Mock;
  let managerQuery: jest.Mock;

  /** Queue per-call return values for the transaction manager's raw queries. */
  const queueManager = (...results: unknown[]): void => {
    results.forEach((r) => managerQuery.mockResolvedValueOnce(r));
  };

  beforeEach(() => {
    managerQuery = jest.fn();
    dataSourceQuery = jest.fn();

    const manager = { query: managerQuery } as unknown as EntityManager;
    const dataSource = {
      query: dataSourceQuery,
      transaction: jest.fn((cb: (m: EntityManager) => Promise<unknown>) =>
        cb(manager),
      ),
    } as unknown as DataSource;

    usage = {
      runWithQuota: jest.fn(
        (_u, _t, _m, work: () => Promise<unknown>) => work() as never,
      ),
      getUsageSummary: jest.fn().mockResolvedValue(USAGE),
    };
    pipeline = { analyze: jest.fn() };

    service = new ScreenshotsService(
      dataSource,
      usage as unknown as UsageService,
      pipeline as unknown as AiPipelineService,
    );
  });

  const validDto = (): CreateScreenshotDto => ({
    ocr_text: 'me: hey\nthem: hi',
    extracted_messages: [
      { sender: MessageSender.Me, content: 'hey' },
      { sender: MessageSender.Them, content: 'hi' },
    ],
  });

  describe('ingest', () => {
    it('persists a new conversation, messages, and screenshot, and reports usage (AC-O1)', async () => {
      // createConversation → nextPosition → insertMessages → insertScreenshot
      queueManager([{ id: 'conv-1' }], [{ next: 0 }], [], [{ id: 'shot-1' }]);

      const res = await service.ingest(USER, Tier.Free, validDto());

      expect(res.screenshot_id).toBe('shot-1');
      expect(res.conversation_id).toBe('conv-1');
      expect(res.extracted_messages).toHaveLength(2);
      expect(res.usage).toEqual(USAGE);

      // Quota was reserved for a screenshot.
      expect(usage.runWithQuota).toHaveBeenCalledWith(
        USER,
        Tier.Free,
        UsageMetric.Screenshot,
        expect.any(Function),
      );

      // Messages were written with the OCR source.
      const insert = managerQuery.mock.calls.find((c) =>
        String(c[0]).includes('INSERT INTO messages'),
      );
      expect(insert).toBeDefined();
      expect(String(insert![0])).toContain("'ocr'");
    });

    it('rejects a blank OCR result without reserving quota (AC-O2)', async () => {
      const dto = { ...validDto(), ocr_text: '   ' };

      await expect(service.ingest(USER, Tier.Free, dto)).rejects.toBeInstanceOf(
        OcrFailedException,
      );
      expect(usage.runWithQuota).not.toHaveBeenCalled();
      expect(managerQuery).not.toHaveBeenCalled();
    });

    it('rejects a payload with no extracted messages without reserving quota (AC-O2)', async () => {
      const dto = { ...validDto(), extracted_messages: [] };

      await expect(service.ingest(USER, Tier.Free, dto)).rejects.toBeInstanceOf(
        OcrFailedException,
      );
      expect(usage.runWithQuota).not.toHaveBeenCalled();
    });

    it('treats messages with only whitespace content as an OCR failure (AC-O2)', async () => {
      const dto = {
        ...validDto(),
        extracted_messages: [{ sender: MessageSender.Me, content: '   ' }],
      };

      await expect(service.ingest(USER, Tier.Free, dto)).rejects.toBeInstanceOf(
        OcrFailedException,
      );
      expect(usage.runWithQuota).not.toHaveBeenCalled();
    });

    it('propagates a quota error and persists nothing when the limit is reached (AC-O3)', async () => {
      usage.runWithQuota.mockRejectedValueOnce(
        new QuotaExceededException(UsageMetric.Screenshot, 5),
      );

      await expect(
        service.ingest(USER, Tier.Free, validDto()),
      ).rejects.toBeInstanceOf(QuotaExceededException);
      expect(managerQuery).not.toHaveBeenCalled();
    });

    it('appends to an owned conversation and 404s on an unowned one', async () => {
      // Ownership check passes, then persist runs.
      dataSourceQuery.mockResolvedValueOnce([{ id: 'conv-9' }]);
      queueManager([{ next: 4 }], [], [{ id: 'shot-2' }]);

      const dto = { ...validDto(), conversation_id: 'conv-9' };
      const res = await service.ingest(USER, Tier.Free, dto);
      expect(res.conversation_id).toBe('conv-9');

      // Unowned conversation → 404, no quota reserved.
      dataSourceQuery.mockResolvedValueOnce([]);
      usage.runWithQuota.mockClear();
      await expect(
        service.ingest(USER, Tier.Free, {
          ...validDto(),
          conversation_id: 'x',
        }),
      ).rejects.toBeInstanceOf(NotFoundException);
      expect(usage.runWithQuota).not.toHaveBeenCalled();
    });
  });

  describe('analyze', () => {
    it('runs the pipeline over the screenshot conversation and returns usage', async () => {
      dataSourceQuery
        .mockResolvedValueOnce([{ conversation_id: 'conv-1' }]) // screenshot lookup
        .mockResolvedValueOnce([
          { sender: 'me', content: 'hey' },
          { sender: 'them', content: 'hi' },
        ]); // messages
      pipeline.analyze.mockResolvedValue({
        summary: 's',
        interest_score: 60,
        suggested_replies: [],
        red_flags: [],
        degraded: false,
      });

      const res = await service.analyze(USER, Tier.Free, 'shot-1');

      expect(pipeline.analyze).toHaveBeenCalledWith(
        {
          messages: [
            { sender: 'me', content: 'hey' },
            { sender: 'them', content: 'hi' },
          ],
        },
        expect.objectContaining({ userId: USER, conversationId: 'conv-1' }),
      );
      expect(res.interest_score).toBe(60);
      expect(res.usage).toEqual(USAGE);
    });

    it('404s when the screenshot is missing or not owned', async () => {
      dataSourceQuery.mockResolvedValueOnce([]);
      await expect(
        service.analyze(USER, Tier.Free, 'missing'),
      ).rejects.toBeInstanceOf(NotFoundException);
    });
  });
});
