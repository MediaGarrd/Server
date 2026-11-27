package wellatleastitried.mediagarrdServer.service;

import java.time.Duration;

public class RateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitedException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
