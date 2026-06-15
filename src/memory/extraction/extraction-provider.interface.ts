/** Injection token for the active {@link MemoryExtractionProvider} binding. */
export const MEMORY_EXTRACTION_PROVIDER = Symbol('MEMORY_EXTRACTION_PROVIDER');

/** Phase-2 memory kinds (§5.8). location/relationship fold into `fact`. */
export type MemoryKind = 'interest' | 'job' | 'birthday' | 'fact';

/** Whether a fact describes the contact ("them") or the user ("me"/global). */
export type FactScope = 'contact' | 'user';

/** A durable fact extracted from a conversation. */
export interface ExtractedFact {
  kind: MemoryKind;
  /** Canonical declarative fact text, e.g. "Works as a chef". */
  content: string;
  /** Extractor confidence in [0,1]; drives active vs pending_review (§5.9). */
  confidence: number;
  scope: FactScope;
}

export interface ExtractionInput {
  messages: Array<{ sender: string; content: string }>;
  contactLabel?: string;
}

/**
 * Provider-abstraction for memory extraction (MS-4 / DAI-149), mirroring the LLM
 * and embedding seams: a deterministic keyless heuristic `stub` for local/test
 * and an LLM-backed implementation for real use. Thin/emoji/spam input must
 * yield `[]` — no hallucinated facts (FR-M3 / AC-M3).
 */
export interface MemoryExtractionProvider {
  readonly name: string;
  extract(input: ExtractionInput): Promise<ExtractedFact[]>;
}
