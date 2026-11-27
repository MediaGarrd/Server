package wellatleastitried.mediagarrdServer.service;

import java.time.Duration;

public class LockedOutException extends RuntimeException {

    private final Duration retryAfter;

    public LockedOutException(Duration retryAfter) {
        super("Too many wrong attempts, try again in " + describe(retryAfter));
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    private static String describe(Duration duration) {
        long minutes = (duration.toSeconds() + 59) / 60;
        return minutes <= 1 ? "a minute" : minutes + " minutes";
    }
}
