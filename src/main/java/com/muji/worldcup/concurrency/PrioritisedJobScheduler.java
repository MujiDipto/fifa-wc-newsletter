package com.muji.worldcup.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Priority-aware thread pool. HIGH-priority tasks run before NORMAL-priority ones;
 * within the same priority, FIFO order is preserved via a sequence counter.
 *
 * Backed by a {@link ThreadPoolExecutor} draining a {@link PriorityBlockingQueue}.
 * Tasks must be submitted via {@link #submit(Callable, Priority)} — they are wrapped
 * in a comparable {@link FutureTask} subclass so the queue can order them.
 */
public class PrioritisedJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(PrioritisedJobScheduler.class);

    public enum Priority { HIGH, NORMAL }

    private static final AtomicLong SEQ = new AtomicLong();

    private static final class PrioritizedTask<T> extends FutureTask<T> implements Comparable<Runnable> {
        private final int priorityOrdinal;
        private final long seq;

        PrioritizedTask(Callable<T> callable, Priority priority) {
            super(callable);
            this.priorityOrdinal = priority.ordinal();
            this.seq = SEQ.getAndIncrement();
        }

        @Override
        public int compareTo(Runnable other) {
            if (other instanceof PrioritizedTask<?> o) {
                int pc = Integer.compare(this.priorityOrdinal, o.priorityOrdinal);
                return pc != 0 ? pc : Long.compare(this.seq, o.seq);
            }
            return 0;
        }
    }

    private final ThreadPoolExecutor executor;

    public PrioritisedJobScheduler(int poolSize) {
        AtomicInteger count = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "pjs-worker-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                });
        log.debug("PrioritisedJobScheduler started with {} workers", poolSize);
    }

    public <T> Future<T> submit(Callable<T> callable, Priority priority) {
        PrioritizedTask<T> task = new PrioritizedTask<>(callable, priority);
        executor.execute(task);
        return task;
    }

    public void shutdown() {
        executor.shutdown();
    }

    public void awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        executor.awaitTermination(timeout, unit);
    }
}
