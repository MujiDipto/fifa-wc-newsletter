package com.muji.worldcup.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * Hand-rolled bounded worker pool: fixed worker threads drain a capacity-capped
 * blocking queue. No library thread pools — the explicit implementation is the point.
 */
public class BoundedWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(BoundedWorkerPool.class);

    private final BlockingQueue<FutureTask<?>> taskQueue;
    private final Thread[] workers;
    private volatile boolean shutdown = false;

    public BoundedWorkerPool(int poolSize, int queueCapacity) {
        this.taskQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.workers = new Thread[poolSize];
        for (int i = 0; i < poolSize; i++) {
            workers[i] = new Thread(this::workerLoop, "wcp-worker-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }
        log.debug("BoundedWorkerPool started: {} workers, queue capacity {}", poolSize, queueCapacity);
    }

    private void workerLoop() {
        while (!shutdown || !taskQueue.isEmpty()) {
            try {
                FutureTask<?> task = taskQueue.poll(200, TimeUnit.MILLISECONDS);
                if (task != null) {
                    task.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.debug("{} exiting", Thread.currentThread().getName());
    }

    public <T> Future<T> submit(Callable<T> callable) {
        if (shutdown) throw new RejectedExecutionException("Pool is shut down");
        FutureTask<T> ft = new FutureTask<>(callable);
        try {
            taskQueue.put(ft);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("Interrupted while enqueuing task", e);
        }
        return ft;
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
