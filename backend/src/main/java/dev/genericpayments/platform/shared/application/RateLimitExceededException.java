package dev.genericpayments.platform.shared.application;
public final class RateLimitExceededException extends RuntimeException {
    private final int retryAfterSeconds;
    public RateLimitExceededException(int retryAfterSeconds) { super("Rate limit exceeded"); this.retryAfterSeconds=retryAfterSeconds; }
    public int retryAfterSeconds() { return retryAfterSeconds; }
}
