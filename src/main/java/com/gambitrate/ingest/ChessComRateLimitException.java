package com.gambitrate.ingest;

public class ChessComRateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public ChessComRateLimitException(long retryAfterSeconds) {
        super("Chess.com rate limit was exhausted. Retry after " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
