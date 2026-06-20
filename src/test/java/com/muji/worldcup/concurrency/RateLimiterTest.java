package com.muji.worldcup.concurrency;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void acquireDoesNotBlockWhenUnderLimit() throws InterruptedException {
        // 10 permits per 1000ms — acquiring 5 should be instant
        RateLimiter limiter = new RateLimiter("test", 10, 1000);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) limiter.acquire();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 500, "Expected < 500ms for 5 acquires under limit, got " + elapsed + "ms");
    }

    @Test
    void acquireThrottlesWhenLimitExceeded() throws InterruptedException {
        // 2 permits per 500ms — acquiring 3 must take at least 500ms
        RateLimiter limiter = new RateLimiter("test", 2, 500);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) limiter.acquire();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 400, "Expected throttling after 2 permits, elapsed=" + elapsed + "ms");
    }

    @Test
    void concurrentAcquiresRespectLimit() throws Exception {
        // 5 permits per 500ms, fire 10 threads simultaneously
        RateLimiter limiter = new RateLimiter("concurrent-test", 5, 500);
        int threads = 10;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        AtomicLong minStart = new AtomicLong(Long.MAX_VALUE);
        AtomicLong maxEnd = new AtomicLong(0);

        for (int i = 0; i < threads; i++) {
            futures.add(exec.submit(() -> {
                try {
                    limiter.acquire();
                    long now = System.currentTimeMillis();
                    minStart.accumulateAndGet(now, Math::min);
                    maxEnd.accumulateAndGet(now, Math::max);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        exec.shutdown();

        long totalWindow = maxEnd.get() - minStart.get();
        // 10 permits at 5/500ms must span at least one window boundary
        assertTrue(totalWindow >= 400,
                "Expected rate limiting to span at least 400ms, got " + totalWindow + "ms");
    }
}
