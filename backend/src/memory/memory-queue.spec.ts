import { Queue } from 'bullmq';
import {
  BullMemoryExtractionQueue,
  MemoryQueue,
  NoopMemoryExtractionQueue,
} from './memory-queue';

describe('Memory extraction queue (producer)', () => {
  it('enqueues a debounced, conversation-keyed job', async () => {
    const add = jest.fn().mockResolvedValue(undefined);
    const queue = new BullMemoryExtractionQueue({ add } as unknown as Queue);

    await queue.enqueue('conv-1');

    expect(add).toHaveBeenCalledTimes(1);
    const [name, data, opts] = add.mock.calls[0];
    expect(name).toBe('extract');
    expect(data).toEqual({ conversationId: 'conv-1' });
    expect(opts.jobId).toBe('conv:conv-1'); // dedupe/debounce key
    expect(opts.delay).toBeGreaterThan(0);
    expect(opts.attempts).toBeGreaterThan(1);
  });

  it('AC-M4: a queue failure is swallowed (fire-and-forget, never propagates)', async () => {
    const add = jest.fn().mockRejectedValue(new Error('redis down'));
    const queue = new BullMemoryExtractionQueue({ add } as unknown as Queue);

    await expect(queue.enqueue('conv-1')).resolves.toBeUndefined();
  });

  it('no-op queue does nothing and never throws', async () => {
    const noop: MemoryQueue = new NoopMemoryExtractionQueue();
    await expect(noop.enqueue('x')).resolves.toBeUndefined();
  });
});
