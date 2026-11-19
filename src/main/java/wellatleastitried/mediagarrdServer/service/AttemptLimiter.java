package wellatleastitried.mediagarrdServer.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

final class AttemptLimiter {

    private final int maxFailures;
    private final Duration lockDuration;
    private final Clock clock;
    private int failures;
    private Instant lockedUntil;

    AttemptLimiter(int maxFailures, Duration lockDuration, Clock clock) {
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    synchronized void checkNotLocked() {
        if (lockedUntil == null) {
            return;
        }
        Instant now = clock.instant();
        if (now.isBefore(lockedUntil)) {
            throw new LockedOutException(Duration.between(now, lockedUntil));
        }
        lockedUntil = null;
        failures = 0;
    }

    synchronized int recordFailure() {
        failures++;
        if (failures >= maxFailures) {
            lockedUntil = clock.instant().plus(lockDuration);
        }
        return failures;
    }

    synchronized boolean isLockedNow() {
        return lockedUntil != null && clock.instant().isBefore(lockedUntil);
    }

    synchronized void reset() {
        failures = 0;
        lockedUntil = null;
    }
}
