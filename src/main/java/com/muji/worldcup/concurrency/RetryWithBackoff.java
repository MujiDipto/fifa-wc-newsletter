package com.muji.worldcup.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

public class RetryWithBackoff {

    private static final Logger log = LoggerFactory.getLogger(RetryWithBackoff.class);

    private final int maxAttempts;
    private final long initialDelayMs;
    private final double multiplier;

    public RetryWithBackoff(int maxAttempts, long initialDelayMs, double multiplier) {
        this.maxAttempts = maxAttempts;
        this.initialDelayMs = initialDelayMs;
        this.multiplier = multiplier;
    }

    public <T> T execute(String taskName, Callable<T> task) throws Exception {
        int attempt = 0;
        long delay = initialDelayMs;
        while (true) {
            try {
                return task.call();
            } catch (Exception e) {
                attempt++;
                if (attempt >= maxAttempts) {
                    log.error("{} failed after {} attempts: {}", taskName, attempt, e.getMessage());
                    throw e;
                }
                log.warn("{} attempt {}/{} failed ({}), retrying in {}ms",
                        taskName, attempt, maxAttempts, e.getMessage(), delay);
                Thread.sleep(delay);
                delay = (long) (delay * multiplier);
            }
        }
    }
}
