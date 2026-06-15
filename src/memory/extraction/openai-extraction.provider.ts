import { Logger } from '@nestjs/common';
import { LlmConfig } from '../../config/configuration';
import {
  ExtractedFact,
  ExtractionInput,
  FactScope,
  MemoryExtractionProvider,
  MemoryKind,
} from './extraction-provider.interface';

const DEFAULT_OPENAI_BASE_URL = 'https://api.openai.com/v1';
const KINDS: MemoryKind[] = ['interest', 'job', 'birthday', 'fact'];

interface ChatCompletionResponse {
  choices?: Array<{ message?: { content?: string | null } }>;
}

/**
 * LLM-backed memory extractor (MS-4 / DAI-149). Asks an OpenAI-compatible model
 * to return a strict JSON array of facts, then validates/clamps the output —
 * unknown kinds, blank content, and out-of-range confidence are dropped so a
 * drifting model can't inject malformed memories. Constructed from `LlmConfig`
 * by the factory in `memory.module.ts`, which only selects it when a key is set.
 */
export class OpenAiMemoryExtractionProvider implements MemoryExtractionProvider {
  readonly name = 'openai';
  private readonly logger = new Logger(OpenAiMemoryExtractionProvider.name);
  private readonly baseUrl: string;
  private readonly apiKey: string;
  private readonly model: string;
  private readonly requestTimeoutMs: number;

  constructor(cfg: LlmConfig) {
    if (!cfg.apiKey) {
      throw new Error('OpenAiMemoryExtractionProvider requires an API key.');
    }
    this.apiKey = cfg.apiKey;
    this.model = cfg.analysisModel?.trim() || 'gpt-4o-mini';
    this.baseUrl = (cfg.baseUrl?.trim() || DEFAULT_OPENAI_BASE_URL).replace(
      /\/+$/,
      '',
    );
    this.requestTimeoutMs = cfg.requestTimeoutMs;
  }

  async extract(input: ExtractionInput): Promise<ExtractedFact[]> {
    const transcript = input.messages
      .map((m) => `${m.sender}: ${m.content}`)
      .join('\n');
    const system = [
      'Extract durable facts about the participants from this chat.',
      `"me" is the user; "them" is the contact${
        input.contactLabel ? ` (${input.contactLabel})` : ''
      }.`,
      'Only include lasting facts (interests, job, birthday, other stable facts).',
      'Ignore small talk, logistics, and anything transient or uncertain.',
      'If there are no durable facts, return an empty array.',
      'Respond with ONLY JSON: {"facts":[{"kind":"interest|job|birthday|fact",',
      '"content":"<short declarative fact>","confidence":0-1,"scope":"contact|user"}]}',
    ].join(' ');

    const raw = await this.complete(system, transcript);
    return this.parse(raw);
  }

  private parse(raw: string): ExtractedFact[] {
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      this.logger.warn(
        'Extractor returned unparseable JSON; emitting nothing.',
      );
      return [];
    }
    const facts = (parsed as { facts?: unknown[] })?.facts;
    if (!Array.isArray(facts)) return [];

    const out: ExtractedFact[] = [];
    for (const f of facts) {
      const rec = f as Record<string, unknown>;
      const kind = rec.kind as MemoryKind;
      const content = typeof rec.content === 'string' ? rec.content.trim() : '';
      const confidence = Number(rec.confidence);
      const scope: FactScope = rec.scope === 'user' ? 'user' : 'contact';
      if (!KINDS.includes(kind) || content.length === 0) continue;
      out.push({
        kind,
        content,
        confidence: Number.isFinite(confidence)
          ? Math.min(1, Math.max(0, confidence))
          : 0.5,
        scope,
      });
    }
    return out;
  }

  private async complete(system: string, user: string): Promise<string> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.requestTimeoutMs);
    try {
      const res = await fetch(`${this.baseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          authorization: `Bearer ${this.apiKey}`,
        },
        body: JSON.stringify({
          model: this.model,
          messages: [
            { role: 'system', content: system },
            { role: 'user', content: user },
          ],
          response_format: { type: 'json_object' },
        }),
        signal: controller.signal,
      });
      if (!res.ok) {
        throw new Error(
          `OpenAI extraction failed (${res.status} ${res.statusText}).`,
        );
      }
      const data = (await res.json()) as ChatCompletionResponse;
      return data.choices?.[0]?.message?.content ?? '';
    } finally {
      clearTimeout(timer);
    }
  }
}
