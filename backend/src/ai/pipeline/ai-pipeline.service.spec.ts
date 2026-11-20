import { BadGatewayException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { AiPipelineService, PipelineContext } from './ai-pipeline.service';
import { Normalizer } from './normalizer';
import { ContextBuilder } from './context-builder';
import { OutputValidator } from '../validation/output-validator';
import { AiRequestLogger } from '../logging/ai-request-logger.service';
import { StubLlmProvider } from '../provider/stub-llm.provider';
import { StubMemoryRetriever } from '../memory/stub-memory-retriever';
import {
  LlmCompletionResult,
  LlmProvider,
} from '../provider/llm-provider.interface';
import { Tone } from '../enums/tone.enum';
import { MessageSender, AiSource } from '../enums/source.enum';
import { OutputLang } from '../enums/output-lang.enum';

const ctx: PipelineContext = { source: AiSource.App };

const conversation = [
  {
    sender: MessageSender.Them,
    content: 'hey, what are you up to this weekend?',
  },
  { sender: MessageSender.Me, content: 'not much yet — you?' },
];

function build(provider: LlmProvider, maxRepairRetries = 1) {
  const requestLog = { record: jest.fn().mockResolvedValue(undefined) };
  const config = {
    getOrThrow: () => ({ maxRepairRetries }),
  } as unknown as ConfigService;

  const service = new AiPipelineService(
    provider,
    new StubMemoryRetriever(),
    new Normalizer(),
    new ContextBuilder(),
    new OutputValidator(),
    requestLog as unknown as AiRequestLogger,
    config,
  );
  return { service, requestLog };
}

describe('AiPipelineService', () => {
  it('generateReplies returns tone-tagged candidates and logs the call (AC-R1)', async () => {
    const { service, requestLog } = build(new StubLlmProvider());
    const replies = await service.generateReplies(
      { messages: conversation, tone: Tone.Flirty, count: 2 },
      ctx,
    );
    expect(replies.length).toBe(2);
    expect(replies.every((r) => r.tone === Tone.Flirty)).toBe(true);
    expect(requestLog.record).toHaveBeenCalled();
    expect(requestLog.record.mock.calls[0][0]).toMatchObject({
      type: 'reply',
      source: 'app',
      provider: 'stub',
      status: 'ok',
    });
  });

  it('rewrite returns improved text (AC-R3)', async () => {
    const { service } = build(new StubLlmProvider());
    const out = await service.rewrite({ text: 'ok', tone: Tone.Funny }, ctx);
    expect(typeof out).toBe('string');
    expect(out.length).toBeGreaterThan(0);
  });

  it('analyze returns a clamped score and required fields (AC-N1)', async () => {
    const { service } = build(new StubLlmProvider());
    const res = await service.analyze({ messages: conversation }, ctx);
    expect(res.summary.length).toBeGreaterThan(0);
    expect(Number.isInteger(res.interest_score)).toBe(true);
    expect(res.interest_score).toBeGreaterThanOrEqual(0);
    expect(res.interest_score).toBeLessThanOrEqual(100);
    expect(res.suggested_replies.length).toBeGreaterThanOrEqual(1);
    expect(Array.isArray(res.red_flags)).toBe(true);
  });

  it('analyze degrades (never throws) when the model returns garbage (AC-N2)', async () => {
    const driftProvider: LlmProvider = {
      name: 'drift',
      async complete(): Promise<LlmCompletionResult> {
        return {
          text: 'totally not json, interest is like a million%',
          provider: 'drift',
          model: 'drift-1',
          tokensIn: 10,
          tokensOut: 10,
        };
      },
    };
    const { service } = build(driftProvider, 1);
    const res = await service.analyze({ messages: conversation }, ctx);
    expect(res.degraded).toBe(true);
    expect(res.interest_score).toBe(0);
    expect(res.summary.length).toBeGreaterThan(0);
  });

  it('reply degrades to a usable fallback on garbage output', async () => {
    const driftProvider: LlmProvider = {
      name: 'drift',
      async complete(): Promise<LlmCompletionResult> {
        return {
          text: '???',
          provider: 'drift',
          model: 'drift-1',
          tokensIn: 1,
          tokensOut: 1,
        };
      },
    };
    const { service } = build(driftProvider, 0);
    const replies = await service.generateReplies(
      { messages: conversation, tone: Tone.Mature, count: 3 },
      ctx,
    );
    expect(replies.length).toBeGreaterThanOrEqual(1);
    expect(replies[0].tone).toBe(Tone.Mature);
  });

  it('maps provider transport failures to 502, not 500', async () => {
    const failing: LlmProvider = {
      name: 'boom',
      async complete(): Promise<LlmCompletionResult> {
        throw new Error('connection reset');
      },
    };
    const { service, requestLog } = build(failing, 0);
    await expect(
      service.analyze({ messages: conversation }, ctx),
    ).rejects.toBeInstanceOf(BadGatewayException);
    expect(requestLog.record.mock.calls[0][0]).toMatchObject({
      status: 'error',
    });
  });
});

/**
 * Android Phase 0: the keyboard's `lang` must reach the provider prompt and its
 * `contact_label` must scope memory retrieval per contact.
 */
describe('AiPipelineService lang + contact scoping (Android Phase 0)', () => {
  const capture = () => {
    const provider = new StubLlmProvider();
    const spy = jest.spyOn(provider, 'complete');
    return { provider, spy };
  };

  it('puts the requested output language into the reply prompt', async () => {
    const { provider, spy } = capture();
    const { service } = build(provider);
    const replies = await service.generateReplies(
      {
        messages: conversation,
        tone: Tone.Friendly,
        count: 2,
        lang: OutputLang.Vi,
      },
      ctx,
    );
    expect(replies.length).toBeGreaterThanOrEqual(1);
    expect(spy.mock.calls[0][0].prompt.system).toContain('in Vietnamese');
  });

  it('puts the requested output language into the rewrite prompt', async () => {
    const { provider, spy } = capture();
    const { service } = build(provider);
    await service.rewrite(
      { text: 'ok', tone: Tone.Funny, lang: OutputLang.En },
      ctx,
    );
    expect(spy.mock.calls[0][0].prompt.system).toContain('in English');
  });

  it('scopes memory retrieval by ctx.contactLabel', async () => {
    const retriever = { retrieve: jest.fn().mockResolvedValue([]) };
    const config = {
      getOrThrow: () => ({ maxRepairRetries: 0 }),
    } as unknown as ConfigService;
    const service = new AiPipelineService(
      new StubLlmProvider(),
      retriever,
      new Normalizer(),
      new ContextBuilder(),
      new OutputValidator(),
      { record: jest.fn() } as unknown as AiRequestLogger,
      config,
    );
    await service.generateReplies(
      { messages: conversation, tone: Tone.Friendly, count: 1 },
      { source: AiSource.Keyboard, userId: 'u1', contactLabel: 'Linh' },
    );
    expect(retriever.retrieve).toHaveBeenCalledWith(
      expect.objectContaining({ userId: 'u1', contactLabel: 'Linh' }),
    );
  });
});
