package com.muji.worldcup.concurrency;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryWithBackoffTest {

    // Fast retry for tests: 1ms initial delay, no meaningful backoff
    private final RetryWithBackoff retry = new RetryWithBackoff(3, 1, 1.0);

    @Test
    void succeedsOnFirstAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String result = retry.execute("task", () -> { calls.incrementAndGet(); return "ok"; });
        assertEquals("ok", result);
        assertEquals(1, calls.get());
    }

    @Test
    void retriesOnFailureThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String result = retry.execute("task", () -> {
            if (calls.incrementAndGet() < 3) throw new RuntimeException("not yet");
            return "done";
        });
        assertEquals("done", result);
        assertEquals(3, calls.get());
    }

    @Test
    void throwsAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                retry.execute("task", () -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("always fail");
                }));
        assertEquals("always fail", ex.getMessage());
        assertEquals(3, calls.get());
    }

    @Test
    void propagatesCorrectExceptionType() {
        assertThrows(IllegalStateException.class, () ->
                retry.execute("task", () -> { throw new IllegalStateException("typed"); }));
    }
}
