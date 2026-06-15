import { Injectable, Logger } from '@nestjs/common';
import { InjectQueue } from '@nestjs/bullmq';
import { Queue } from 'bullmq';

/** Injection token for the memory-extraction queue (real or no-op binding). */
export const MEMORY_EXTRACTION_QUEUE = Symbol('MEMORY_EXTRACTION_QUEUE');

/** BullMQ queue name; shared by the producer and the processor. */
export const MEMORY_EXTRACTION_QUEUE_NAME = 'memory-extraction';

export interface MemoryExtractionJob {
  conversationId: string;
}

/** Producer contract used by conversation-write paths to trigger extraction. */
export interface MemoryQueue {
  enqueue(conversationId: string): Promise<void>;
}

/** How long to coalesce rapid updates to the same conversation (§5.2 debounce). */
const DEBOUNCE_MS = 5000;

/**
 * BullMQ-backed producer (MS-4 / DAI-149). Enqueuing is fire-and-forget and
 * fully guarded: a Redis/queue failure is logged and swallowed so it can never
 * add latency or a failure mode to the caller's request (AC-M4). Jobs are keyed
 * by conversation id and delayed so a burst of updates debounces to one run.
 */
@Injectable()
export class BullMemoryExtractionQueue implements MemoryQueue {
  private readonly logger = new Logger(BullMemoryExtractionQueue.name);

  constructor(
    @InjectQueue(MEMORY_EXTRACTION_QUEUE_NAME)
    private readonly queue: Queue<MemoryExtractionJob>,
  ) {}

  async enqueue(conversationId: string): Promise<void> {
    try {
      await this.queue.add(
        'extract',
        { conversationId },
        {
          jobId: `conv:${conversationId}`,
          delay: DEBOUNCE_MS,
          attempts: 3,
          backoff: { type: 'exponential', delay: 2000 },
          removeOnComplete: true,
          removeOnFail: 100,
        },
      );
    } catch (err) {
      // AC-M4: extraction is best-effort background work; never propagate.
      this.logger.error(
        `Failed to enqueue extraction for conversation ${conversationId}; skipping.`,
        err instanceof Error ? err.stack : String(err),
      );
    }
  }
}

/**
 * No-op producer bound when extraction is disabled (no Redis). Keeps the
 * conversation-write paths wiring-complete without requiring a queue.
 */
@Injectable()
export class NoopMemoryExtractionQueue implements MemoryQueue {
  async enqueue(): Promise<void> {
    // intentionally does nothing
  }
}
