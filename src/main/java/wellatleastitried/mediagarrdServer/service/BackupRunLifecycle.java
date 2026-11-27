package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import wellatleastitried.mediagarrdServer.model.BackupRunResult;

@Component
public class BackupRunLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupRunLifecycle.class);

    private final RunStatusBroadcaster broadcaster;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object transitionLock = new Object();
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();

    public BackupRunLifecycle(RunStatusBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @FunctionalInterface
    public interface RunBody {
        BackupRunResult execute(Run run);
    }

    public final class Run {

        private final Instant startedAt;
        private boolean started;

        private Run(Instant startedAt) {
            this.startedAt = startedAt;
        }

        public void markStarted() {
            if (started) {
                return;
            }
            started = true;
            publishStarted(startedAt);
        }
    }

    public BackupRunResult run(Instant startedAt, RunBody body) {
        synchronized (transitionLock) {
            if (!running.compareAndSet(false, true)) {
                LOGGER.warn("runBackup() called while backup already in progress, rejecting");
                throw new RunRejectedException("A backup run is already in progress");
            }
        }
        Run run = new Run(startedAt);
        String finalStatus = FAILED;
        try {
            configLock.readLock().lock();
            try {
                BackupRunResult result = body.execute(run);
                finalStatus = result.status();
                return result;
            } finally {
                configLock.readLock().unlock();
            }
        } finally {
            synchronized (transitionLock) {
                running.set(false);
                if (run.started) {
                    publishFinished(finalStatus, startedAt);
                }
            }
        }
    }

    public <T> T whileIdle(Supplier<T> configChange) {
        if (!configLock.writeLock().tryLock()) {
            throw new BackupInProgressException("Configuration cannot be changed while a backup is running, try again once it finishes");
        }
        try {
            return configChange.get();
        } finally {
            configLock.writeLock().unlock();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void publishStarted(Instant startedAt) {
        try {
            broadcaster.publishStarted(startedAt);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to publish run-started event", e);
        }
    }

    private void publishFinished(String status, Instant startedAt) {
        try {
            broadcaster.publishFinished(status, startedAt, Instant.now());
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to publish run-finished event", e);
        }
    }
}
