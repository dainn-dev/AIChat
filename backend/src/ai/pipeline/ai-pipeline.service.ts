import {
  BadGatewayException,
  Inject,
  Injectable,
  Logger,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { LlmConfig } from '../../config/configuration';
import {
  LLM_PROVIDER,
  LlmCompletionResult,
  LlmProvider,
  LlmPrompt,
} from '../provider/llm-provider.interface';
import {
  MEMORY_RETRIEVER,
  MemoryRetriever,
} from '../memory/memory-retriever.interface';
import { Normalizer } from './normalizer';
import {
  AnalyzeContext,
  ContextBuilder,
  ReplyContext,
  RewriteContext,
  TranslateContext,
} from './context-builder';
import { OutputValidator } from '../validation/output-validator';
import { AiRequestLogger } from '../logging/ai-request-logger.service';
import { AiMessageDto } from '../dto/ai-message.dto';
import { AnalyzeResponse } from '../dto/analyze.dto';
import { ReplyCandidate } from '../dto/reply.dto';
import { Tone } from '../enums/tone.enum';
import { AiRequestType, AiSource } from '../enums/source.enum';

export interface PipelineContext {
  source: AiSource;
  userId?: string;
  conversationId?: string;
  /** Active contact for memory scoping (MS-3); optional, defaults to global. */
  contactLabel?: string;
}

export interface ReplyInput {
  messages: AiMessageDto[];
  tone: Tone;
  count: number;
  userGoal?: string;
  relationshipStage?: string;
}

export interface AnalyzeInput {
  messages: AiMessageDto[];
  userGoal?: string;
  relationshipStage?: string;
}

export interface RewriteInput {
  text: string;
  tone: Tone;
}

export interface TranslateInput {
  text: string;
  targetLang: string;
  sourceLang?: string;
}

/**
 * The single unified AI pipeline (DAI-124 §1.2): normalize → memory retrieval
 * (stubbed to `[]` in Phase 1) → context build → LLM, behind the provider
 * abstraction. Every entry point validates/clamps provider output and records
 * an audit row, so callers (the `/ai/*` endpoints) get a guaranteed-valid,
 * already-logged result.
 */
@Injectable()
export class AiPipelineService {
  private readonly logger = new Logger(AiPipelineService.name);
  private readonly maxRepairRetries: number;

  constructor(
    @Inject(LLM_PROVIDER) private readonly provider: LlmProvider,
    @Inject(MEMORY_RETRIEVER) private readonly memory: MemoryRetriever,
    private readonly normalizer: Normalizer,
    private readonly contextBuilder: ContextBuilder,
    private readonly validator: OutputValidator,
    private readonly requestLog: AiRequestLogger,
    config: ConfigService,
  ) {
    const llm = config.getOrThrow<LlmConfig>('llm');
    this.maxRepairRetries = Math.max(0, llm.maxRepairRetries);
  }

  async generateReplies(
    input: ReplyInput,
    ctx: PipelineContext,
  ): Promise<ReplyCandidate[]> {
    const messages = this.normalizer.normalize(input.messages);
    const memories = await this.memory.retrieve({
      userId: ctx.userId,
      contactLabel: ctx.contactLabel,
      conversationText: this.flatten(messages),
      topK: 5,
    });

    const replyCtx: ReplyContext = {
      messages,
      memories,
      tone: input.tone,
      count: input.count,
      userGoal: input.userGoal,
      relationshipStage: input.relationshipStage,
    };
    const prompt = this.contextBuilder.buildReplyPrompt(replyCtx);

    const { parsed, result } = await this.completeJson(
      prompt,
      AiRequestType.Reply,
      ctx,
    );

    const candidates = this.validator.toReplyCandidates(
      parsed,
      input.tone,
      input.count,
    );
    // Drift fallback: always return at least one usable reply (AC-R1).
    if (candidates.length === 0) {
      await this.logStatusOverride(
        ctx,
        AiRequestType.Reply,
        result,
        'degraded',
      );
      return [
        {
          tone: input.tone,
          text: "Hey! I'd love to keep this going — what's next?",
        },
      ];
    }
    return candidates;
  }

  async analyze(
    input: AnalyzeInput,
    ctx: PipelineContext,
  ): Promise<Omit<AnalyzeResponse, 'usage'>> {
    const messages = this.normalizer.normalize(input.messages);
    const memories = await this.memory.retrieve({
      userId: ctx.userId,
      contactLabel: ctx.contactLabel,
      conversationText: this.flatten(messages),
      topK: 5,
    });

    const analyzeCtx: AnalyzeContext = {
      messages,
      memories,
      userGoal: input.userGoal,
      relationshipStage: input.relationshipStage,
    };
    const prompt = this.contextBuilder.buildAnalyzePrompt(analyzeCtx);

    const { parsed } = await this.completeJson(
      prompt,
      AiRequestType.Analysis,
      ctx,
    );

    // Always coerces to a valid response — never throws on drift (AC-N2).
    return this.validator.toAnalyzeResponse(parsed);
  }

  async rewrite(input: RewriteInput, ctx: PipelineContext): Promise<string> {
    const rewriteCtx: RewriteContext = { text: input.text, tone: input.tone };
    const prompt = this.contextBuilder.buildRewritePrompt(rewriteCtx);

    const { result } = await this.complete(prompt, AiRequestType.Rewrite, ctx);
    const text = result.text.trim();
    return text.length > 0 ? text : input.text;
  }

  /** Translate `text` into `targetLang` (keyboard surface, P4-1 / DAI-136). */
  async translate(
    input: TranslateInput,
    ctx: PipelineContext,
  ): Promise<string> {
    const translateCtx: TranslateContext = {
      text: input.text,
      targetLang: input.targetLang,
      sourceLang: input.sourceLang,
    };
    const prompt = this.contextBuilder.buildTranslatePrompt(translateCtx);

    const { result } = await this.complete(
      prompt,
      AiRequestType.Translate,
      ctx,
    );
    const text = result.text.trim();
    // Degrade to the original text rather than returning empty on drift.
    return text.length > 0 ? text : input.text;
  }

  /**
   * Calls the provider expecting JSON, with bounded repair retries on
   * unparseable output. Returns the parsed value (possibly `null` after
   * exhausting retries — callers degrade gracefully rather than 500).
   */
  private async completeJson(
    prompt: LlmPrompt,
    type: AiRequestType,
    ctx: PipelineContext,
  ): Promise<{
    parsed: unknown | null;
    result: LlmCompletionResult;
    status: 'ok' | 'degraded';
  }> {
    let lastResult: LlmCompletionResult | null = null;

    for (let attempt = 0; attempt <= this.maxRepairRetries; attempt++) {
      const { result } = await this.complete(prompt, type, ctx, attempt > 0);
      lastResult = result;
      const parsed = this.validator.parseJsonLoose(result.text);
      if (parsed !== null) {
        return { parsed, result, status: 'ok' };
      }
      this.logger.warn(
        `Provider returned unparseable JSON for ${type} (attempt ${attempt + 1}).`,
      );
    }

    // Exhausted retries: degrade, never throw.
    return { parsed: null, result: lastResult!, status: 'degraded' };
  }

  /** Single provider call wrapped in timing + audit logging. */
  private async complete(
    prompt: LlmPrompt,
    type: AiRequestType,
    ctx: PipelineContext,
    isRetry = false,
  ): Promise<{ result: LlmCompletionResult }> {
    const startedAt = Date.now();
    try {
      const result = await this.provider.complete({
        prompt,
        type,
        // Reply/analysis expect JSON; rewrite/translate return plain text.
        expectJson:
          type !== AiRequestType.Rewrite && type !== AiRequestType.Translate,
      });
      await this.requestLog.record({
        userId: ctx.userId,
        conversationId: ctx.conversationId,
        type,
        source: ctx.source,
        provider: result.provider,
        model: result.model,
        tokensIn: result.tokensIn,
        tokensOut: result.tokensOut,
        latencyMs: Date.now() - startedAt,
        status: 'ok',
      });
      return { result };
    } catch (err) {
      await this.requestLog.record({
        userId: ctx.userId,
        conversationId: ctx.conversationId,
        type,
        source: ctx.source,
        provider: this.provider.name,
        model: 'unknown',
        tokensIn: 0,
        tokensOut: 0,
        latencyMs: Date.now() - startedAt,
        status: 'error',
        errorCode: isRetry ? 'LLM_RETRY_FAILED' : 'LLM_UPSTREAM_ERROR',
      });
      this.logger.error(
        `LLM provider "${this.provider.name}" failed for ${type}`,
        err instanceof Error ? err.stack : String(err),
      );
      // Transport/upstream failure (distinct from model drift) → 502.
      throw new BadGatewayException('AI provider is temporarily unavailable.');
    }
  }

  private async logStatusOverride(
    ctx: PipelineContext,
    type: AiRequestType,
    result: LlmCompletionResult,
    status: 'degraded',
  ): Promise<void> {
    await this.requestLog.record({
      userId: ctx.userId,
      conversationId: ctx.conversationId,
      type,
      source: ctx.source,
      provider: result.provider,
      model: result.model,
      tokensIn: result.tokensIn,
      tokensOut: result.tokensOut,
      latencyMs: 0,
      status,
      errorCode: 'OUTPUT_DEGRADED',
    });
  }

  private flatten(messages: { sender: string; content: string }[]): string {
    return messages.map((m) => `${m.sender}: ${m.content}`).join('\n');
  }
}
