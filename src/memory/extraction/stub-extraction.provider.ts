import { Injectable } from '@nestjs/common';
import {
  ExtractedFact,
  ExtractionInput,
  FactScope,
  MemoryExtractionProvider,
  MemoryKind,
} from './extraction-provider.interface';

interface Pattern {
  re: RegExp;
  kind: MemoryKind;
  confidence: number;
  render: (capture: string) => string;
}

/**
 * Deterministic, keyless memory extractor (mirrors the stub LLM/embedding
 * providers). It recognizes a handful of first-person statements via regex and
 * emits structured facts, attributing them to the contact ("them") or the user
 * ("me"). This is not real NLP, but it is *stable* and *honest*: thin, emoji, or
 * spam input matches nothing and yields `[]` (AC-M3), so the worker can be
 * exercised end-to-end with zero external dependencies.
 */
@Injectable()
export class StubMemoryExtractionProvider implements MemoryExtractionProvider {
  readonly name = 'stub';

  private static readonly PATTERNS: Pattern[] = [
    {
      re: /\bmy birthday is\s+(.+)/i,
      kind: 'birthday',
      confidence: 0.85,
      render: (c) => `Birthday is ${c}`,
    },
    {
      re: /\bi was born (?:on|in)\s+(.+)/i,
      kind: 'birthday',
      confidence: 0.8,
      render: (c) => `Born ${c}`,
    },
    {
      re: /\bi work (?:as|at)\s+(.+)/i,
      kind: 'job',
      confidence: 0.8,
      render: (c) => `Works ${c.startsWith('at') ? c : `as ${c}`}`,
    },
    {
      re: /\bi(?:'m| am)\s+a[n]?\s+([a-z][a-z ]+)/i,
      kind: 'job',
      confidence: 0.75,
      render: (c) => `Works as a ${c}`,
    },
    {
      re: /\bi (?:love|enjoy|really like|like|am into)\s+(.+)/i,
      kind: 'interest',
      confidence: 0.75,
      render: (c) => `Enjoys ${c}`,
    },
    {
      re: /\bi live in\s+(.+)/i,
      kind: 'fact',
      confidence: 0.8,
      render: (c) => `Lives in ${c}`,
    },
    {
      re: /\bi have (?:a|an)\s+(.+)/i,
      kind: 'fact',
      confidence: 0.6, // low → pending_review (§5.9)
      render: (c) => `Has a ${c}`,
    },
  ];

  async extract(input: ExtractionInput): Promise<ExtractedFact[]> {
    const facts: ExtractedFact[] = [];
    const seen = new Set<string>();

    for (const msg of input.messages) {
      const content = (msg.content ?? '').trim();
      if (!this.hasSubstance(content)) continue; // thin/emoji/spam → skip (AC-M3)
      const scope: FactScope = msg.sender === 'me' ? 'user' : 'contact';

      for (const p of StubMemoryExtractionProvider.PATTERNS) {
        const m = content.match(p.re);
        if (!m) continue;
        const capture = this.cleanCapture(m[1]);
        if (!capture) continue;
        const text = p.render(capture);
        const dedupeKey = `${scope}:${p.kind}:${text.toLowerCase()}`;
        if (seen.has(dedupeKey)) continue;
        seen.add(dedupeKey);
        facts.push({
          kind: p.kind,
          content: text,
          confidence: p.confidence,
          scope,
        });
        break; // one fact per message keeps attribution clean
      }
    }
    return facts;
  }

  /** Require real lexical content — guards against emoji/one-word/spam noise. */
  private hasSubstance(content: string): boolean {
    const words = content.toLowerCase().match(/[a-z]{2,}/g) ?? [];
    return content.length >= 6 && words.length >= 3;
  }

  /** Trim to the first clause, strip trailing punctuation, cap length. */
  private cleanCapture(raw: string): string {
    const firstClause = raw.split(/[.!?;\n]/)[0];
    const cleaned = firstClause
      .replace(/\s+/g, ' ')
      .trim()
      .replace(/[,]+$/, '');
    return cleaned.length > 60 ? cleaned.slice(0, 60).trim() : cleaned;
  }
}
