import { Injectable } from '@nestjs/common';
import { Tone } from '../enums/tone.enum';
import { ReplyCandidate } from '../dto/reply.dto';
import { AnalyzeResponse, RedFlag } from '../dto/analyze.dto';

/**
 * Server-side validation/repair of LLM output (DAI-124 §1.4 FR-N3, edge case
 * "LLM output drift"; AC-N2). The cardinal rule: model drift must NEVER surface
 * as a 500 to the client. These helpers parse loosely, clamp, and fall back to
 * safe defaults so the response contract always holds.
 */
@Injectable()
export class OutputValidator {
  /**
   * Best-effort JSON extraction from a raw model string. Handles markdown code
   * fences and leading/trailing prose by isolating the first balanced JSON
   * object or array. Returns `null` when nothing parseable is found (caller
   * decides whether to retry or degrade).
   */
  parseJsonLoose(raw: string): unknown | null {
    if (!raw) return null;

    const candidates: string[] = [];
    const trimmed = raw.trim();
    candidates.push(trimmed);

    const fence = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i);
    if (fence?.[1]) candidates.push(fence[1].trim());

    const objSlice = this.firstBalanced(trimmed, '{', '}');
    if (objSlice) candidates.push(objSlice);
    const arrSlice = this.firstBalanced(trimmed, '[', ']');
    if (arrSlice) candidates.push(arrSlice);

    for (const c of candidates) {
      try {
        return JSON.parse(c);
      } catch {
        // try the next candidate
      }
    }
    return null;
  }

  /** Clamp any model value into an integer in [0, 100]; defaults to 0. */
  clampScore(value: unknown): number {
    let n: number;
    if (typeof value === 'number') n = value;
    else if (typeof value === 'string') n = parseFloat(value);
    else n = NaN;

    if (!Number.isFinite(n)) return 0;
    n = Math.round(n);
    if (n < 0) return 0;
    if (n > 100) return 100;
    return n;
  }

  /**
   * Coerce a parsed object into a guaranteed-valid {@link AnalyzeResponse}.
   * `parsed` may be `null` (unparseable) — in that case a safe degraded result
   * is returned. `degraded` is true whenever we had to substitute defaults.
   */
  toAnalyzeResponse(parsed: unknown): Omit<AnalyzeResponse, 'usage'> {
    if (parsed === null || typeof parsed !== 'object') {
      return this.degradedAnalysis();
    }
    const obj = parsed as Record<string, unknown>;

    const summary =
      typeof obj.summary === 'string' && obj.summary.trim().length > 0
        ? obj.summary.trim()
        : '';
    const suggested = this.coerceCandidates(obj.suggested_replies);
    const redFlags = this.coerceRedFlags(obj.red_flags);

    const degraded = summary === '';

    return {
      summary: summary || 'Not enough context to summarize this conversation.',
      interest_score: this.clampScore(obj.interest_score),
      // Guarantee at least one suggested reply (AC-N1).
      suggested_replies:
        suggested.length > 0
          ? suggested
          : [{ tone: Tone.Friendly, text: 'Tell me more!' }],
      red_flags: redFlags,
      degraded,
    };
  }

  /**
   * Extract reply candidates from parsed reply output, tagging each with the
   * requested tone (the service owns tone — AC-R1 guarantees the tag) and
   * capping to `count`. Returns `[]` when nothing usable was found so the
   * caller can retry/degrade.
   */
  toReplyCandidates(
    parsed: unknown,
    tone: Tone,
    count: number,
  ): ReplyCandidate[] {
    let rawList: unknown[] = [];
    if (Array.isArray(parsed)) {
      rawList = parsed;
    } else if (parsed && typeof parsed === 'object') {
      const r = (parsed as Record<string, unknown>).replies;
      if (Array.isArray(r)) rawList = r;
    }

    const texts: string[] = [];
    for (const item of rawList) {
      if (typeof item === 'string' && item.trim()) {
        texts.push(item.trim());
      } else if (item && typeof item === 'object') {
        const t = (item as Record<string, unknown>).text;
        if (typeof t === 'string' && t.trim()) texts.push(t.trim());
      }
      if (texts.length >= count) break;
    }

    return texts.map((text) => ({ tone, text }));
  }

  private degradedAnalysis(): Omit<AnalyzeResponse, 'usage'> {
    return {
      summary: 'Not enough context to summarize this conversation.',
      interest_score: 0,
      suggested_replies: [{ tone: Tone.Friendly, text: 'Tell me more!' }],
      red_flags: [],
      degraded: true,
    };
  }

  private coerceCandidates(value: unknown): ReplyCandidate[] {
    if (!Array.isArray(value)) return [];
    const out: ReplyCandidate[] = [];
    for (const item of value) {
      if (!item || typeof item !== 'object') continue;
      const o = item as Record<string, unknown>;
      const text = typeof o.text === 'string' ? o.text.trim() : '';
      if (!text) continue;
      const tone = this.coerceTone(o.tone);
      out.push({ tone, text });
    }
    return out;
  }

  private coerceRedFlags(value: unknown): RedFlag[] {
    if (!Array.isArray(value)) return [];
    const out: RedFlag[] = [];
    for (const item of value) {
      if (typeof item === 'string' && item.trim()) {
        out.push({ code: 'flag', message: item.trim() });
      } else if (item && typeof item === 'object') {
        const o = item as Record<string, unknown>;
        const message = typeof o.message === 'string' ? o.message.trim() : '';
        if (!message) continue;
        const code = typeof o.code === 'string' && o.code ? o.code : 'flag';
        out.push({ code, message });
      }
    }
    return out;
  }

  private coerceTone(value: unknown): Tone {
    if (typeof value === 'string') {
      const match = Object.values(Tone).find(
        (t) => t.toLowerCase() === value.toLowerCase(),
      );
      if (match) return match;
    }
    return Tone.Friendly;
  }

  /** Returns the first balanced `open..close` slice, or null. */
  private firstBalanced(s: string, open: string, close: string): string | null {
    const start = s.indexOf(open);
    if (start < 0) return null;
    let depth = 0;
    for (let i = start; i < s.length; i++) {
      if (s[i] === open) depth++;
      else if (s[i] === close) {
        depth--;
        if (depth === 0) return s.slice(start, i + 1);
      }
    }
    return null;
  }
}
