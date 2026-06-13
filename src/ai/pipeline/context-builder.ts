import { Injectable } from '@nestjs/common';
import { LlmPrompt } from '../provider/llm-provider.interface';
import { RetrievedMemory } from '../memory/memory-retriever.interface';
import { NormalizedMessage } from './normalizer';
import { Tone } from '../enums/tone.enum';

export interface ReplyContext {
  messages: NormalizedMessage[];
  memories: RetrievedMemory[];
  tone: Tone;
  count: number;
  userGoal?: string;
  relationshipStage?: string;
}

export interface AnalyzeContext {
  messages: NormalizedMessage[];
  memories: RetrievedMemory[];
  userGoal?: string;
  relationshipStage?: string;
}

export interface RewriteContext {
  text: string;
  tone: Tone;
}

/**
 * Step 3 of the pipeline (DAI-124 §1.2 FR-P4): assemble the provider prompt from
 * the current messages, retrieved memories, relationship stage and user goal.
 * Emits a `{system, user}` pair. The system prompt pins the exact JSON contract
 * the output-validator expects, so any compliant provider's response parses.
 */
@Injectable()
export class ContextBuilder {
  buildReplyPrompt(ctx: ReplyContext): LlmPrompt {
    const system = [
      `You are a messaging assistant that drafts replies on behalf of "me".`,
      `Write ${ctx.count} candidate reply/replies in a ${ctx.tone} tone.`,
      `Each reply must be natural, concise, and ready to send.`,
      `Respond with ONLY valid JSON of the form:`,
      `{"replies":[{"text":"..."}]}`,
    ].join(' ');

    return { system, user: this.renderContext(ctx) };
  }

  buildAnalyzePrompt(ctx: AnalyzeContext): LlmPrompt {
    const system = [
      `You analyze a chat conversation from the perspective of "me".`,
      `Return ONLY valid JSON of the form:`,
      `{"summary":string,"interest_score":integer 0-100,`,
      `"suggested_replies":[{"tone":string,"text":string}],`,
      `"red_flags":[{"code":string,"message":string}]}.`,
      `interest_score is how interested "them" appears to be (0-100).`,
      `If there is not enough context, say so in summary and use a low score.`,
    ].join(' ');

    return { system, user: this.renderContext(ctx) };
  }

  buildRewritePrompt(ctx: RewriteContext): LlmPrompt {
    const system =
      `Rewrite the user's draft message into a ${ctx.tone} tone. ` +
      `Keep the original intent. Respond with ONLY the rewritten message text, no quotes or preamble.`;
    return { system, user: `Draft: ${ctx.text}` };
  }

  private renderContext(ctx: ReplyContext | AnalyzeContext): string {
    const parts: string[] = [];

    if (ctx.relationshipStage) {
      parts.push(`Relationship stage: ${ctx.relationshipStage}`);
    }
    if (ctx.userGoal) {
      parts.push(`My goal: ${ctx.userGoal}`);
    }
    if (ctx.memories.length > 0) {
      parts.push(
        'Relevant memories:\n' +
          ctx.memories.map((m) => `- (${m.kind}) ${m.content}`).join('\n'),
      );
    }

    parts.push(
      'Conversation:\n' +
        ctx.messages.map((m) => `${m.sender}: ${m.content}`).join('\n'),
    );

    return parts.join('\n\n');
  }
}
