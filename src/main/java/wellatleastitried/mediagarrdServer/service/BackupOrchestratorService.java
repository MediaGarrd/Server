package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.formatTime;
import static wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.recordCurrentTime;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.PARTIAL;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;

@Service
public class BackupOrchestratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupOrchestratorService.class);

    private static final int RUNNER_POOL_SIZE = 6;
    private static final long RUNNER_IDLE_TTL_SECONDS = 30;
    private static final long SCHEDULED_CHECK_INTERVAL_MS = 30000;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final ServiceBackupRunner runner;
    private final BackupArchiveService archiveService;
    private final BackupScheduleService scheduleService;
    private final DatabaseService db;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();
    private final ExecutorService runnerExecutor = createRunnerExecutor();

    public BackupOrchestratorService(
        ServiceBackupRunner runner,
        BackupArchiveService archiveService,
        BackupScheduleService scheduleService,
        DatabaseService db
    ) {
        this.runner = runner;
        this.archiveService = archiveService;
        this.scheduleService = scheduleService;
        this.db = db;
    }

    private static ExecutorService createRunnerExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            RUNNER_POOL_SIZE,
            RUNNER_POOL_SIZE,
            RUNNER_IDLE_TTL_SECONDS,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "backup-runner-" + THREAD_COUNTER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Scheduled(fixedDelay = SCHEDULED_CHECK_INTERVAL_MS)
    public void scheduledRun() {
        if (running.get()) {
            LOGGER.debug("Scheduled check skipped, backup already in progress");
            return;
        }
        if (!scheduleService.dueNow()) {
            return;
        }
        LOGGER.info("Scheduled backup triggered, next run at {}", scheduleService.getNextRun());
        try {
            runBackup();
        } catch (RuntimeException ex) {
            LOGGER.error("Scheduled backup run failed", ex);
        }
    }

    public BackupRunResult runBackup() {
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("runBackup() called while backup already in progress, rejecting");
            throw new RunRejectedException("A backup run is already in progress");
        }

        configLock.readLock().lock();
        Path runDirectory = null;
        try {
            List<ServiceDefinition> services = db.fetchServices();
            if (services.isEmpty()) {
                LOGGER.warn("No services are configured, aborting backup run before creating a run directory");
                throw new RunRejectedException("No services are configured for backup");
            }

            scheduleService.markRunStarted();
            runDirectory = archiveService.createRunDirectory();
            final Path runDir = runDirectory;
            Instant startedAt = Instant.now();

            LOGGER.info("Backup run started: runDir={}, services={}",
                runDir, services.stream().map(ServiceDefinition::name).toList());

            List<CompletableFuture<BackupServiceResult>> futures = services.stream()
                .map(service -> CompletableFuture
                    .supplyAsync(() -> runner.run(service, runDir), runnerExecutor)
                    .exceptionally(t -> {
                        Throwable cause = unwrap(t);
                        LOGGER.warn("Runner {} failed unexpectedly", service.name(), cause);
                        return failedResult(service.name(), cause);
                    }))
                .toList();

            List<BackupServiceResult> serviceRunResults = futures.stream()
                .map(CompletableFuture::join)
                .toList();

            List<BackupServiceResult> incomplete = serviceRunResults.stream()
                .filter(r -> !COMPLETED.equals(r.status()))
                .toList();
            int succeeded = services.size() - incomplete.size();
            LOGGER.info("All runners finished: succeeded={}/{}", succeeded, services.size());
            incomplete.forEach(r -> LOGGER.warn("Runner did not complete (non-fatal): {} ({})", r.serviceName(), r.status()));

            String errorMessage = incomplete.stream()
                .map(r -> r.serviceName() + ": " + Objects.requireNonNullElse(r.errorMessage(), "no details"))
                .collect(Collectors.joining("\n"));

            if (succeeded == 0 && incomplete.stream().allMatch(r -> FAILED.equals(r.status()))) {
                String runId = "failed-" + UUID.randomUUID();
                serviceRunResults.forEach(r -> r.setId(runId));
                db.addNewBackupRecord(new BackupRunResult(
                    runId, formatTime(startedAt), recordCurrentTime(), FAILED, serviceRunResults, null, errorMessage));
                LOGGER.error("Every service failed, no archive created (run {}):\n{}", runId, errorMessage);
                throw new IllegalStateException("Every service failed, no archive created: " + errorMessage);
            }

            BackupArchive archive = archiveService.createArchive(runDirectory);
            serviceRunResults.forEach(r -> r.setId(archive.id()));

            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            LOGGER.info("Backup run complete: archive={}, size={}B, duration={}ms, services={}",
                archive.fileName(), archive.sizeBytes(), durationMs,
                serviceRunResults.stream().map(BackupServiceResult::serviceName).toList());

            String status = succeeded == services.size() ? COMPLETED : succeeded > 0 ? PARTIAL : FAILED;

            BackupRunResult backupRecord = new BackupRunResult(
                archive.id(), formatTime(startedAt), recordCurrentTime(), status,
                serviceRunResults, archive, errorMessage);
            db.addNewBackupRecord(backupRecord);
            return backupRecord;
        } finally {
            if (runDirectory != null) {
                archiveService.removeRunDirectory(runDirectory);
                LOGGER.debug("Run directory cleaned up: {}", runDirectory);
            }
            configLock.readLock().unlock();
            running.set(false);
        }
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }

    private static BackupServiceResult failedResult(String serviceName, Throwable cause) {
        String timestamp = recordCurrentTime();
        return new BackupServiceResult(
            null,
            FAILED,
            timestamp,
            timestamp,
            serviceName,
            Objects.requireNonNullElse(cause.getMessage(), cause.toString())
        );
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

    public Duration getInterval() {
        return scheduleService.getInterval();
    }

    public Duration updateInterval(Duration duration) {
        return scheduleService.updateInterval(duration);
    }

    @PreDestroy
    public void shutdownRunnerExecutor() {
        runnerExecutor.shutdownNow();
    }

    public static class RunRejectedException extends IllegalStateException {
        public RunRejectedException(String message) {
            super(message);
        }
    }

    public static class BackupInProgressException extends IllegalStateException {
        public BackupInProgressException(String message) {
            super(message);
        }
    }
}
