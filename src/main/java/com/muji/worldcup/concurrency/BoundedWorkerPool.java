package com.muji.worldcup.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-size thread pool with a bounded task queue. Backed by {@link ThreadPoolExecutor}.
 * Submissions block when the queue is full (CallerRunsPolicy would change throughput
 * characteristics, so we keep a blocking put via LinkedBlockingQueue).
 */
public class BoundedWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(BoundedWorkerPool.class);

    private final ThreadPoolExecutor executor;

    public BoundedWorkerPool(int poolSize, int queueCapacity) {
        AtomicInteger count = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "wcp-worker-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                });
        log.debug("BoundedWorkerPool started: {} workers, queue capacity {}", poolSize, queueCapacity);
    }

    public <T> Future<T> submit(Callable<T> callable) {
        return executor.submit(callable);
    }

    /** Exposes the underlying executor for use with {@link CompletableFuture#supplyAsync}. */
    public ExecutorService executor() {
        return executor;
    }

    public void shutdown() {
        executor.shutdown();
    }

    public void awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        executor.awaitTermination(timeout, unit);
    }
}
