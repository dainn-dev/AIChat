import { BadGatewayException } from '@nestjs/common';
import { AiPipelineService } from '../ai/pipeline/ai-pipeline.service';
import { AiSource, MessageSender } from '../ai/enums/source.enum';
import { OutputLang } from '../ai/enums/output-lang.enum';
import { Tone } from '../ai/enums/tone.enum';
import { Tier } from '../common/tiers';
import { ConversationsService } from '../conversations/conversations.service';
import { UsageService } from '../usage/usage.service';
import { KeyboardReplyDto } from './dto/keyboard.dto';
import { KeyboardService, PERSIST_TIMEOUT_MS } from './keyboard.service';

/**
 * Keyboard reply orchestration (Android Phase 0): lang/contact_label reach the
 * pipeline, persistence runs only after a successful generation, and a slow or
 * failing persistence never fails or stalls the reply.
 */
const USAGE = {
  replies_used: 1,
  replies_limit: 20,
  screenshots_used: 0,
  screenshots_limit: 5,
};
const user = { sub: 'user-1', email: 'a@b.com', tier: Tier.Free };
const suggestions = [{ tone: Tone.Friendly, text: 'ok nhé' }];
const STICKER_TAGS = ['ok', 'thanks'];

function build() {
  const pipeline = {
    generateRepliesWithStickers: jest
      .fn()
      .mockResolvedValue({ replies: suggestions, stickerTags: STICKER_TAGS }),
    rewrite: jest.fn().mockResolvedValue('rewritten'),
    write: jest.fn().mockResolvedValue('Chúc sếp sinh nhật vui vẻ!'),
    correct: jest.fn().mockResolvedValue('Tối nay bạn rảnh không?'),
    analyze: jest.fn().mockResolvedValue({
      summary: 'Hai bạn đang hẹn đi cà phê.',
      interest_score: 72,
      suggested_replies: suggestions,
      red_flags: [{ code: 'pressure', message: 'Hơi hối thúc' }],
      degraded: false,
    }),
  };
  const usage = {
    runWithQuota: jest.fn(
      async (_u: string, _t: unknown, _m: unknown, fn: () => unknown) => fn(),
    ),
    getUsageSummary: jest.fn().mockResolvedValue(USAGE),
  };
  const conversations = {
    persistKeyboardCapture: jest.fn().mockResolvedValue('conv-1'),
  };
  const service = new KeyboardService(
    pipeline as unknown as AiPipelineService,
    usage as unknown as UsageService,
    conversations as unknown as ConversationsService,
  );
  return { service, pipeline, usage, conversations };
}

const dto = (over: Partial<KeyboardReplyDto> = {}): KeyboardReplyDto => ({
  platform: 'zalo',
  contact_label: 'Linh',
  conversation: [{ sender: MessageSender.Them, content: 'hi' }],
  tone: Tone.Friendly,
  ...over,
});

describe('KeyboardService.reply (Android Phase 0)', () => {
  afterEach(() => jest.useRealTimers());

  it('passes lang to the pipeline and contact_label as the memory scope', async () => {
    const { service, pipeline } = build();
    await service.reply(user, dto({ lang: OutputLang.Vi }));
    expect(pipeline.generateRepliesWithStickers).toHaveBeenCalledWith(
      expect.objectContaining({ lang: OutputLang.Vi, count: 3 }),
      {
        source: AiSource.Keyboard,
        userId: 'user-1',
        contactLabel: 'Linh',
      },
    );
  });

  it('returns suggestions, sticker_tags, conversation_id and usage', async () => {
    const { service, conversations } = build();
    const res = await service.reply(user, dto({ remember: true }));
    expect(res).toEqual({
      suggestions,
      sticker_tags: STICKER_TAGS,
      conversation_id: 'conv-1',
      usage: USAGE,
    });
    expect(conversations.persistKeyboardCapture).toHaveBeenCalledWith({
      userId: 'user-1',
      platform: 'zalo',
      contactLabel: 'Linh',
      remember: true,
      messages: dto().conversation,
    });
  });

  it('returns sticker_tags: [] when the pipeline suggests none', async () => {
    const { service, pipeline } = build();
    pipeline.generateRepliesWithStickers.mockResolvedValue({
      replies: suggestions,
      stickerTags: [],
    });
    const res = await service.reply(user, dto());
    expect(res.sticker_tags).toEqual([]);
  });

  it('returns conversation_id: null when persistence is gated off', async () => {
    const { service, conversations } = build();
    conversations.persistKeyboardCapture.mockResolvedValue(null);
    const res = await service.reply(user, dto());
    expect(res.conversation_id).toBeNull();
    expect(res.suggestions).toEqual(suggestions);
  });

  it('does NOT persist when generation fails', async () => {
    const { service, pipeline, conversations } = build();
    pipeline.generateRepliesWithStickers.mockRejectedValue(
      new BadGatewayException('AI provider is temporarily unavailable.'),
    );
    await expect(service.reply(user, dto())).rejects.toBeInstanceOf(
      BadGatewayException,
    );
    expect(conversations.persistKeyboardCapture).not.toHaveBeenCalled();
  });

  it('still replies when persistence rejects unexpectedly', async () => {
    const { service, conversations } = build();
    conversations.persistKeyboardCapture.mockRejectedValue(new Error('boom'));
    const res = await service.reply(user, dto());
    expect(res.suggestions).toEqual(suggestions);
    expect(res.conversation_id).toBeNull();
  });

  it('stops waiting for a slow persistence after PERSIST_TIMEOUT_MS', async () => {
    jest.useFakeTimers();
    const { service, conversations } = build();
    conversations.persistKeyboardCapture.mockReturnValue(
      new Promise(() => undefined), // never settles
    );
    const pending = service.reply(user, dto());
    await jest.advanceTimersByTimeAsync(PERSIST_TIMEOUT_MS);
    const res = await pending;
    expect(res.conversation_id).toBeNull();
    expect(res.suggestions).toEqual(suggestions);
  });
});

describe('KeyboardService.rewrite (Android Phase 0)', () => {
  it('passes lang through to the pipeline', async () => {
    const { service, pipeline } = build();
    await service.rewrite(user, {
      text: 'ok',
      tone: Tone.Funny,
      lang: OutputLang.En,
    });
    expect(pipeline.rewrite).toHaveBeenCalledWith(
      { text: 'ok', tone: Tone.Funny, lang: OutputLang.En },
      { source: AiSource.Keyboard, userId: 'user-1' },
    );
  });
});

describe('KeyboardService.summarize', () => {
  const summarizeDto = {
    conversation: [{ sender: MessageSender.Them, content: 'cafe ko?' }],
    lang: OutputLang.Vi,
    contact_label: 'Linh',
  };

  it('meters one generation and passes lang and the memory scope', async () => {
    const { service, pipeline, usage } = build();
    await service.summarize(user, summarizeDto);
    expect(usage.runWithQuota).toHaveBeenCalledTimes(1);
    expect(pipeline.analyze).toHaveBeenCalledWith(
      { messages: summarizeDto.conversation, lang: OutputLang.Vi },
      { source: AiSource.Keyboard, userId: 'user-1', contactLabel: 'Linh' },
    );
  });

  it('returns the summary, score, red flags and usage without suggested replies', async () => {
    const { service } = build();
    const res = await service.summarize(user, summarizeDto);
    expect(res).toEqual({
      summary: 'Hai bạn đang hẹn đi cà phê.',
      interest_score: 72,
      red_flags: [{ code: 'pressure', message: 'Hơi hối thúc' }],
      degraded: false,
      usage: USAGE,
    });
  });

  it('never stores the conversation', async () => {
    const { service, conversations } = build();
    await service.summarize(user, summarizeDto);
    expect(conversations.persistKeyboardCapture).not.toHaveBeenCalled();
  });
});

describe('KeyboardService.correct', () => {
  it('meters one generation and returns the corrected text with usage', async () => {
    const { service, pipeline, usage } = build();
    const res = await service.correct(user, { text: 'toi nay ban ranh ko' });
    expect(usage.runWithQuota).toHaveBeenCalledTimes(1);
    expect(pipeline.correct).toHaveBeenCalledWith(
      { text: 'toi nay ban ranh ko' },
      { source: AiSource.Keyboard, userId: 'user-1' },
    );
    expect(res).toEqual({ text: 'Tối nay bạn rảnh không?', usage: USAGE });
  });
});

describe('KeyboardService.write', () => {
  it('meters one generation and passes instruction and lang', async () => {
    const { service, pipeline, usage } = build();
    const res = await service.write(user, {
      instruction: 'chúc sinh nhật sếp',
      lang: OutputLang.Vi,
    });
    expect(usage.runWithQuota).toHaveBeenCalledTimes(1);
    expect(pipeline.write).toHaveBeenCalledWith(
      { instruction: 'chúc sinh nhật sếp', lang: OutputLang.Vi },
      { source: AiSource.Keyboard, userId: 'user-1' },
    );
    expect(res).toEqual({ text: 'Chúc sếp sinh nhật vui vẻ!', usage: USAGE });
  });
});
