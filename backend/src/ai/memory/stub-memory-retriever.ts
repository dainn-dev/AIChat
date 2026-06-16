import { Injectable } from '@nestjs/common';
import { MemoryRetriever, RetrievedMemory } from './memory-retriever.interface';

/**
 * Phase-1 no-op memory retriever. Always returns `[]` (DAI-124 §1.2 FR-P3) —
 * the Memory Engine ships in Phase 2 (DAI-121). Kept as a real binding so the
 * pipeline's retrieval step is wired and observable today. The `MemoryQuery`
 * arg is part of the interface but unused here, so it is omitted.
 */
@Injectable()
export class StubMemoryRetriever implements MemoryRetriever {
  async retrieve(): Promise<RetrievedMemory[]> {
    return [];
  }
}
