import { Logger } from '@nestjs/common';
import { Processor, WorkerHost } from '@nestjs/bullmq';
import { Job } from 'bullmq';
import {
  MEMORY_EXTRACTION_QUEUE_NAME,
  MemoryExtractionJob,
} from './memory-queue';
import { MemoryExtractionService } from './memory-extraction.service';

/**
 * BullMQ worker (MS-4 / DAI-149) — consumes extraction jobs off the user-facing
 * path and runs {@link MemoryExtractionService}. A thrown error lets BullMQ
 * retry per the job's backoff; it never touches the request that enqueued it.
 */
@Processor(MEMORY_EXTRACTION_QUEUE_NAME)
export class MemoryExtractionProcessor extends WorkerHost {
  private readonly logger = new Logger(MemoryExtractionProcessor.name);

  constructor(private readonly extraction: MemoryExtractionService) {
    super();
  }

  async process(job: Job<MemoryExtractionJob>): Promise<void> {
    const { conversationId } = job.data;
    const outcome =
      await this.extraction.extractForConversation(conversationId);
    this.logger.log(
      `Extraction for conversation ${conversationId}: ${outcome.status} ` +
        `(${outcome.factsWritten}/${outcome.factsExtracted} written).`,
    );
  }
}
