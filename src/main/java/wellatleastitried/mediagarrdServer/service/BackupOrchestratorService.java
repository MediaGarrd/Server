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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.services.runner.Runner;

@Service
public class BackupOrchestratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupOrchestratorService.class);

    private static final int RUNNER_POOL_SIZE = 6;
    private static final long RUNNER_IDLE_TTL_SECONDS = 30;
    private static final long SCHEDULED_CHECK_INTERVAL_MS = 30000;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final MediaGarrdProperties properties;
    private final RunnerFactory runnerFactory;
    private final BackupArchiveService archiveService;
    private final BackupScheduleService scheduleService;
    private final DatabaseService db;
    // private final DockerService dockerService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService runnerExecutor = createRunnerExecutor();

    // TODO:
    public BackupOrchestratorService(
        MediaGarrdProperties properties,
        RunnerFactory runnerFactory,
        BackupArchiveService archiveService,
        BackupScheduleService scheduleService,
        DatabaseService db/*,
        DockerService dockerService*/
    ) {
        this.properties = properties;
        this.runnerFactory = runnerFactory;
        this.archiveService = archiveService;
        this.scheduleService = scheduleService;
        this.db = db;
        // this.dockerService = dockerService
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
            throw new IllegalStateException("A backup run is already in progress");
        }

        Path runDirectory = null;
        try {
            List<Runner> runners = runnerFactory.build(properties.getServiceConfigs());
            if (runners.isEmpty()) {
                LOGGER.warn("No services are configured, aborting backup run before creating a run directory");
                throw new IllegalStateException("No services are configured for backup");
            }

            scheduleService.markRunStarted();
            runDirectory = archiveService.createRunDirectory();
            final Path runDir = runDirectory;
            Instant startedAt = Instant.now();

            LOGGER.info("Backup run started: runDir={}, services={}",
                runDir, runners.stream().map(Runner::getServiceName).toList());

            List<CompletableFuture<BackupServiceResult>> futures = runners.stream()
                .map(r -> CompletableFuture
                    .supplyAsync(() -> runSingleRunner(r, runDir), runnerExecutor)
                    .exceptionally(t -> {
                        Throwable cause = unwrap(t);
                        LOGGER.warn("Runner {} failed unexpectedly", r.getServiceName(), cause);
                        return failedResult(r.getServiceName(), cause);
                    }))
                .toList();

            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Backup run interrupted", ex);
            } catch (ExecutionException ex) {
                // Unreachable
                throw new IllegalStateException("Unexpected failure collecting runner results", ex.getCause());
            }

            List<BackupServiceResult> serviceRunResults = futures.stream()
                .map(CompletableFuture::join)
                .toList();

            for (BackupServiceResult result : serviceRunResults) {
                if (COMPLETED.equals(result.status())) {
                    LOGGER.info("Runner succeeded: {}", result.serviceName());
                } else {
                    LOGGER.warn("Runner failed (non-fatal): {}", result.serviceName());
                }
            }

            int successfulRunnerCount = (int) serviceRunResults.stream()
                .filter(r -> COMPLETED.equals(r.status()))
                .count();
            LOGGER.info("All runners finished: succeeded={}/{}", successfulRunnerCount, runners.size());

            String errorMessage = serviceRunResults.stream()
                .filter(r -> !COMPLETED.equals(r.status()))
                .map(r -> r.serviceName() + ": " + Objects.requireNonNullElse(r.errorMessage(), "no details"))
                .collect(Collectors.joining("\n"));

            BackupArchive archive = archiveService.createArchive(runDirectory);
            serviceRunResults.forEach(r -> r.setId(archive.id()));

            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            LOGGER.info("Backup run complete: archive={}, size={}B, duration={}ms, services={}",
                archive.fileName(), archive.sizeBytes(), durationMs,
                serviceRunResults.stream().map(BackupServiceResult::serviceName).toList());

            String status = successfulRunnerCount == runners.size() ? COMPLETED
                : successfulRunnerCount > 0 ? PARTIAL
                : FAILED;

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

    private BackupServiceResult runSingleRunner(Runner runner, Path runDirectory) {
        String serviceName = runner.getServiceName();
        LOGGER.info("[{}] Runner starting", serviceName);
        long startNanos = System.nanoTime();
        try {
            runner.run(runDirectory);
            LOGGER.info("[{}] Runner finished in {}ms", serviceName, elapsedMs(startNanos));
        } catch (RuntimeException ex) {
            LOGGER.error("[{}] Runner failed after {}ms, continuing with remaining services",
                serviceName, elapsedMs(startNanos), ex);
        }

        return new BackupServiceResult(
            null,
            runner.getRunnerStatus(),
            runner.getRunnerStartTime(),
            runner.getRunnerEndTime(),
            runner.getServiceName(),
            runner.getRunnerException()
        );
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
