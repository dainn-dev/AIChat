import { ContextBuilder } from '../ai/pipeline/context-builder';
import { NormalizedMessage } from '../ai/pipeline/normalizer';
import { MessageSender } from '../ai/enums/source.enum';
import { Tone } from '../ai/enums/tone.enum';
import { SensitiveDataFilter } from '../memory/privacy/sensitive-data.filter';

/**
 * Turns stored conversations into OpenAI chat fine-tuning examples
 * (docs/model-training.md). Pure: no DB, no network, unit-tested.
 *
 * One example per reply the user really sent: the messages before it are the
 * conversation, the reply is the answer. The prompt is built by the same
 * `ContextBuilder.buildReplyPrompt` the keyboard uses at inference time and
 * the answer uses the same JSON shape, so a model trained on these keeps
 * producing output the pipeline's validator accepts.
 */

/** OpenAI chat fine-tuning line (one JSON object per line of the .jsonl). */
export interface FineTuneExample {
  messages: Array<{ role: 'system' | 'user' | 'assistant'; content: string }>;
}

export interface TrainingExampleOptions {
  /** Messages of context before the reply (most recent kept). */
  contextMessages: number;
  /** Consecutive "me" bubbles merged into one reply, at most. */
  maxReplyBubbles: number;
  /** Replies longer than this (after merging) are skipped. */
  maxReplyChars: number;
}

export const DEFAULT_TRAINING_OPTIONS: TrainingExampleOptions = {
  contextMessages: 20,
  maxReplyBubbles: 3,
  maxReplyChars: 500,
};

/**
 * The tone written into every training prompt. Real replies carry no tone
 * label; one fixed tone keeps the examples consistent (the model learns the
 * user's own voice as the "Friendly" baseline).
 */
export const TRAINING_TONE = Tone.Friendly;

export interface ExampleStats {
  examples: number;
  /** Windows skipped because a message fell in a sensitive drop-category. */
  skippedSensitive: number;
  /** Replies skipped for being too long. */
  skippedTooLong: number;
}

/**
 * Examples for one conversation ([messages] oldest first). A reply qualifies
 * when it is a run of "me" messages directly after a "them" message. Every
 * message goes through [filter]: inline identifiers (phone, email, card,
 * address) are masked, and a window containing a drop-category message
 * (health, finances, minors) is skipped entirely.
 */
export function buildExamples(
  messages: NormalizedMessage[],
  filter: SensitiveDataFilter,
  builder: ContextBuilder,
  options: TrainingExampleOptions = DEFAULT_TRAINING_OPTIONS,
): { examples: FineTuneExample[]; stats: ExampleStats } {
  const scanned = messages.map((m) => {
    const result = filter.scan(m.content);
    return {
      sender: m.sender,
      content: result.content.trim(),
      dropped: result.dropped,
    };
  });
  const examples: FineTuneExample[] = [];
  const stats: ExampleStats = {
    examples: 0,
    skippedSensitive: 0,
    skippedTooLong: 0,
  };

  for (let i = 1; i < scanned.length; i++) {
    const startsReply =
      scanned[i].sender === MessageSender.Me &&
      scanned[i - 1].sender === MessageSender.Them;
    if (!startsReply) continue;

    let end = i;
    while (
      end + 1 < scanned.length &&
      scanned[end + 1].sender === MessageSender.Me &&
      end + 1 - i < options.maxReplyBubbles
    ) {
      end++;
    }
    const replyParts = scanned.slice(i, end + 1);
    const context = scanned
      .slice(Math.max(0, i - options.contextMessages), i)
      .filter((m) => m.content.length > 0);
    const window = [...context, ...replyParts];
    if (window.some((m) => m.dropped)) {
      stats.skippedSensitive++;
      continue;
    }
    const reply = replyParts
      .map((m) => m.content)
      .filter((c) => c.length > 0)
      .join('\n');
    if (reply.length === 0 || context.length === 0) continue;
    if (reply.length > options.maxReplyChars) {
      stats.skippedTooLong++;
      continue;
    }

    const prompt = builder.buildReplyPrompt({
      messages: context.map((m) => ({ sender: m.sender, content: m.content })),
      memories: [],
      tone: TRAINING_TONE,
      count: 1,
    });
    examples.push({
      messages: [
        { role: 'system', content: prompt.system },
        { role: 'user', content: prompt.user },
        {
          role: 'assistant',
          content: JSON.stringify({ replies: [{ text: reply }] }),
        },
      ],
    });
    stats.examples++;
  }
  return { examples, stats };
}
