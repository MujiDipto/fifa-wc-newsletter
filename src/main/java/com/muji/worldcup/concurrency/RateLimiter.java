package com.muji.worldcup.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple sliding-window rate limiter. Tracks request count within a rolling
 * period; blocks the calling thread when the limit is reached until the window resets.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final String name;
    private final int requestsPerPeriod;
    private final long periodMs;

    private int count = 0;
    private long windowStart = System.currentTimeMillis();

    public RateLimiter(String name, int requestsPerPeriod, long periodMs) {
        this.name = name;
        this.requestsPerPeriod = requestsPerPeriod;
        this.periodMs = periodMs;
    }

    public synchronized void acquire() throws InterruptedException {
        long now = System.currentTimeMillis();
        if (now - windowStart >= periodMs) {
            windowStart = now;
            count = 0;
        }
        if (count >= requestsPerPeriod) {
            long sleepMs = periodMs - (now - windowStart) + 50; // +50ms buffer
            log.debug("{} rate limit reached, sleeping {}ms", name, sleepMs);
            Thread.sleep(sleepMs);
            windowStart = System.currentTimeMillis();
            count = 0;
        }
        count++;
    }
}
