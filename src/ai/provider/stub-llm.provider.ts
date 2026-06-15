import { Injectable } from '@nestjs/common';
import { AiRequestType } from '../enums/source.enum';
import {
  LlmCompletionRequest,
  LlmCompletionResult,
  LlmProvider,
} from './llm-provider.interface';

/**
 * Deterministic, keyless LLM provider used until epic Decision #1 (concrete
 * provider + keys) lands. It returns schema-valid output so the entire
 * pipeline — context build, output validation/clamping, response shaping — is
 * exercisable and testable end-to-end with zero external dependencies.
 *
 * Output is derived deterministically from the prompt (stable across runs), and
 * NLP-grade quality is intentionally out of scope: the service layer guarantees
 * the response contract (tone tagging, score clamping, non-empty summary)
 * regardless of what any provider returns. Swapping in Claude / GPT / Gemini is
 * a binding change in `ai.module.ts` plus config — no pipeline changes.
 */
@Injectable()
export class StubLlmProvider implements LlmProvider {
  readonly name = 'stub';

  async complete(request: LlmCompletionRequest): Promise<LlmCompletionResult> {
    const seed = this.hash(request.prompt.user + request.prompt.system);
    const text = this.render(request, seed);

    return {
      text,
      provider: this.name,
      model: 'stub-deterministic-v1',
      // Coarse token estimate (~4 chars/token) so audit logging has plausible,
      // non-zero figures; replaced by real provider usage when wired.
      tokensIn: Math.ceil(
        (request.prompt.system.length + request.prompt.user.length) / 4,
      ),
      tokensOut: Math.ceil(text.length / 4),
    };
  }

  private render(request: LlmCompletionRequest, seed: number): string {
    switch (request.type) {
      case AiRequestType.Translate:
        // Deterministic stand-in; the real provider returns the translation.
        return `(translated) ${request.prompt.user}`;
      case AiRequestType.Rewrite:
        return 'Here is a polished take on that — clear, warm, and easy to read.';
      case AiRequestType.Reply:
        return JSON.stringify({
          replies: [
            { text: 'That sounds great — what did you have in mind?' },
            { text: 'Love it. When works for you?' },
            { text: "Ha, you read my mind. Let's do it." },
          ],
        });
      case AiRequestType.Analysis:
      default:
        return JSON.stringify({
          summary:
            'A casual, back-and-forth exchange with mutual interest and a relaxed tone.',
          interest_score: seed % 101,
          suggested_replies: [
            { tone: 'Friendly', text: 'Sounds good — talk soon!' },
            { tone: 'Flirty', text: "Can't wait. You free this weekend? 😊" },
          ],
          red_flags:
            seed % 3 === 0
              ? [
                  {
                    code: 'slow_response',
                    message: 'Replies are spaced far apart.',
                  },
                ]
              : [],
        });
    }
  }

  /** Small deterministic non-cryptographic hash (djb2). */
  private hash(input: string): number {
    let h = 5381;
    for (let i = 0; i < input.length; i++) {
      h = (h * 33) ^ input.charCodeAt(i);
    }
    return Math.abs(h);
  }
}
