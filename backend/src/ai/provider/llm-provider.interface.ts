import { AiRequestType } from '../enums/source.enum';

/** Injection token for the active {@link LlmProvider} binding. */
export const LLM_PROVIDER = Symbol('LLM_PROVIDER');

/**
 * A single prompt turn passed to the provider. `system` carries instructions,
 * `user` carries the assembled context (see context-builder).
 */
export interface LlmPrompt {
  system: string;
  user: string;
}

export interface LlmCompletionRequest {
  prompt: LlmPrompt;
  /** Drives provider-side routing (e.g. reply vs analysis model). */
  type: AiRequestType;
  /** Hint that the caller expects strict JSON back (analysis). */
  expectJson?: boolean;
  /** Soft cap on output size; providers may honor it as max_tokens. */
  maxOutputTokens?: number;
}

export interface LlmCompletionResult {
  text: string;
  provider: string;
  model: string;
  tokensIn: number;
  tokensOut: number;
}

/**
 * Provider-abstraction interface (DAI-124 §5.1 / epic Decision #1). Every
 * concrete provider — Claude, GPT, Gemini, or the keyless dev stub — implements
 * this single method, so the LLM is swappable behind config without touching
 * the pipeline. The concrete Phase-1 provider is gated on Decision #1; this
 * interface is built regardless, exactly as the WS-4 scope requires.
 */
export interface LlmProvider {
  readonly name: string;
  complete(request: LlmCompletionRequest): Promise<LlmCompletionResult>;
}
