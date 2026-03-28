package com.pronote.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Enforces a minimum delay between outbound HTTP requests, with random jitter,
 * to reduce the risk of triggering Pronote's rate-limiting or bot detection.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final long minDelayMs;
    private final long jitterMs;

    public RateLimiter(long minDelayMs, long jitterMs) {
        this.minDelayMs = minDelayMs;
        this.jitterMs = jitterMs;
    }

    /**
     * Sleeps for {@code minDelayMs + random(0, jitterMs)} milliseconds.
     * The calling thread is interrupted if the JVM shuts down during the sleep.
     */
    public void await() {
        long delay = minDelayMs;
        if (jitterMs > 0) {
            delay += ThreadLocalRandom.current().nextLong(0, jitterMs + 1);
        }
        log.debug("Rate limiter sleeping {}ms", delay);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
