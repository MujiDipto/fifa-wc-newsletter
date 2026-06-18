package com.muji.worldcup.concurrency;

/**
 * Thrown to signal that a failure is permanent and should not be retried
 * by {@link RetryWithBackoff}. Use for config errors, 4xx (non-429) responses,
 * and any other case where retrying would never help.
 */
public class NonRetryableException extends RuntimeException {
    public NonRetryableException(String message) {
        super(message);
    }

    public NonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
