package com.muji.worldcup.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hand-rolled priority scheduler. Jobs are enqueued with a Priority and drained
 * by a fixed pool of worker threads in HIGH-before-NORMAL order. Within the same
 * priority, FIFO order is preserved via a sequence counter.
 *
 * Used in Phase 2 to process subscribed teams/players before the rest of the field.
 */
public class PrioritisedJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(PrioritisedJobScheduler.class);

    public enum Priority { HIGH, NORMAL }

    private static final class Job<T> implements Comparable<Job<?>> {
        final FutureTask<T> future;
        final Priority priority;
        final long seq;

        Job(FutureTask<T> future, Priority priority, long seq) {
            this.future = future;
            this.priority = priority;
            this.seq = seq;
        }

        @Override
        public int compareTo(Job<?> other) {
            int pc = Integer.compare(this.priority.ordinal(), other.priority.ordinal());
            return pc != 0 ? pc : Long.compare(this.seq, other.seq);
        }
    }

    private final PriorityBlockingQueue<Job<?>> queue;
    private final Thread[] workers;
    private final AtomicLong seqGen = new AtomicLong();
    private volatile boolean shutdown = false;

    public PrioritisedJobScheduler(int poolSize) {
        this.queue = new PriorityBlockingQueue<>();
        this.workers = new Thread[poolSize];
        for (int i = 0; i < poolSize; i++) {
            workers[i] = new Thread(this::workerLoop, "pjs-worker-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }
        log.debug("PrioritisedJobScheduler started with {} workers", poolSize);
    }

    public <T> Future<T> submit(Callable<T> callable, Priority priority) {
        if (shutdown) throw new RejectedExecutionException("Scheduler is shut down");
        FutureTask<T> ft = new FutureTask<>(callable);
        queue.put(new Job<>(ft, priority, seqGen.getAndIncrement()));
        return ft;
    }

    private void workerLoop() {
        while (!shutdown || !queue.isEmpty()) {
            try {
                Job<?> job = queue.poll(200, TimeUnit.MILLISECONDS);
                if (job != null) {
                    log.debug("{} executing {} priority job", Thread.currentThread().getName(), job.priority);
                    job.future.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.debug("{} exiting", Thread.currentThread().getName());
    }

    public void shutdown() {
        shutdown = true;
    }

    public void awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNs = System.nanoTime() + unit.toNanos(timeout);
        for (Thread w : workers) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
            if (remainingMs <= 0) break;
            w.join(remainingMs);
        }
    }
}
