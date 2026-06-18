package com.muji.worldcup.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedWorkerPoolTest {

    private BoundedWorkerPool pool;

    @BeforeEach
    void setUp() {
        pool = new BoundedWorkerPool(2, 10);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void submittedTaskReturnsResult() throws Exception {
        Future<Integer> f = pool.submit(() -> 42);
        assertEquals(42, f.get(2, TimeUnit.SECONDS));
    }

    @Test
    void multipleTasksRunConcurrently() throws Exception {
        int count = 8;
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int val = i;
            futures.add(pool.submit(() -> val * 2));
        }
        for (int i = 0; i < count; i++) {
            assertEquals(i * 2, futures.get(i).get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void exceptionInTaskSurfacesThroughFuture() {
        Future<Void> f = pool.submit(() -> { throw new RuntimeException("boom"); });
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> f.get(2, TimeUnit.SECONDS));
        assertEquals("boom", ex.getCause().getMessage());
    }

    @Test
    void shutdownPreventsNewSubmissions() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.SECONDS);
        assertThrows(RejectedExecutionException.class, () -> pool.submit(() -> 1));
    }

    @Test
    void allTasksCompleteBeforeShutdown() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        int count = 6;
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(() -> { counter.incrementAndGet(); return null; }));
        }
        for (Future<?> f : futures) f.get(3, TimeUnit.SECONDS);
        assertEquals(count, counter.get());
    }
}
