import { Injectable } from '@nestjs/common';

export interface SensitiveScanResult {
  /** Content after inline PII redaction (unchanged if nothing matched). */
  content: string;
  /** True when the whole fact falls in a drop-category and must not be stored. */
  dropped: boolean;
  /** Drop category that triggered exclusion, if any. */
  droppedCategory?: string;
  /** True when inline PII was masked. */
  redacted: boolean;
}

interface DropRule {
  category: string;
  re: RegExp;
}
interface RedactRule {
  label: string;
  re: RegExp;
}

/**
 * Sensitive-category filter for the memory write path (MS-6 / DAI-151 §5.7).
 * Memories are durable, derived profiles of third parties built from private
 * messages, so high-risk categories are kept out of the store:
 *  - DROP entirely: facts about health, finances, or minors — we do not build a
 *    profile of these even if a model emits them.
 *  - REDACT inline: phone, email, payment-card-like numbers, and street
 *    addresses are masked so direct identifiers never persist.
 *
 * Heuristic by design (defaults pending §5.7 compliance sign-off); tuned to
 * over-redact rather than leak. Applied at extraction (MS-4) before persistence.
 */
@Injectable()
export class SensitiveDataFilter {
  private static readonly DROP_RULES: DropRule[] = [
    {
      category: 'health',
      re: /\b(diagnos\w*|cancer|hiv|aids|depression|anxiety|medication|prescription|disease|disorder|pregnan\w*|therapy|psychiatr\w*|disabilit\w*|disabled)\b/i,
    },
    {
      category: 'financial',
      re: /\b(salary|income|bank account|credit score|net worth|in debt|bankrupt\w*|\bssn\b|social security)\b/i,
    },
    {
      // "is a minor", "underage", or an explicit age under 18.
      category: 'minor',
      re: /\b(under ?age|is a minor|(?:1[0-7]|[1-9])\s*(?:years? old|yo)\b)/i,
    },
  ];

  private static readonly REDACT_RULES: RedactRule[] = [
    { label: 'email', re: /\b[\w.+-]+@[\w-]+\.[\w.-]+\b/gi },
    // `card` before `phone`: a long run of pure digits is a card/ID, and the
    // phone pattern would otherwise swallow it.
    { label: 'card', re: /\b\d{13,19}\b/g },
    { label: 'phone', re: /\+?\d[\d\s().-]{7,}\d/g },
    {
      label: 'address',
      re: /\b\d{1,5}\s+[A-Za-z][A-Za-z ]*\b(?:street|st|avenue|ave|road|rd|boulevard|blvd|lane|ln|drive|dr)\b/gi,
    },
  ];

  scan(content: string): SensitiveScanResult {
    const text = content ?? '';
    for (const rule of SensitiveDataFilter.DROP_RULES) {
      if (rule.re.test(text)) {
        return {
          content: text,
          dropped: true,
          droppedCategory: rule.category,
          redacted: false,
        };
      }
    }

    let redacted = false;
    let out = text;
    for (const rule of SensitiveDataFilter.REDACT_RULES) {
      out = out.replace(rule.re, () => {
        redacted = true;
        return `[redacted-${rule.label}]`;
      });
    }
    return { content: out, dropped: false, redacted };
  }
}
