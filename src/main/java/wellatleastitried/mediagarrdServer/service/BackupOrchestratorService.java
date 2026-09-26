package wellatleastitried.mediagarrdServer.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;
import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.services.runner.Runner;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static wellatleastitried.mediagarrdServer.utilities.DatabaseUtils.Status.*;
import static wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.*;

@Service
public class BackupOrchestratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupOrchestratorService.class);

    private final MediaGarrdProperties properties;
    private final RunnerFactory runnerFactory;
    private final BackupArchiveService archiveService;
    private final BackupScheduleService scheduleService;
    private final DatabaseService db;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService runnerExecutor = new ThreadPoolExecutor(
        1, // core threads
        6, // max threads
        30, // ttl
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>()
    );

    public BackupOrchestratorService(
        MediaGarrdProperties properties,
        RunnerFactory runnerFactory,
        BackupArchiveService archiveService,
        BackupScheduleService scheduleService,
        DatabaseService db
    ) {
        this.properties = properties;
        this.runnerFactory = runnerFactory;
        this.archiveService = archiveService;
        this.scheduleService = scheduleService;
        this.db = db;
    }

    @Scheduled(fixedDelay = 30000)
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

        scheduleService.markRunStarted();

        Path runDirectory = archiveService.createRunDirectory();
        Instant startedAt = Instant.now();
        List<Runner> runners = runnerFactory.build(properties.getServiceConfigs());
        List<BackupServiceResult> serviceRunResults = new ArrayList<>();

        LOGGER.info(
            "Backup run started: runDir={}, services={}",
            runDirectory,
            runners.stream().map(Runner::getServiceName).toList());

        try {
            String errorMessage = "";
            List<Future<BackupServiceResult>> futures = new ArrayList<>();
            for (Runner runner : runners) {
                LOGGER.info("Submitting runner for service: {}", runner.getServiceName());
                futures.add(runnerExecutor.submit(() -> runSingleRunner(runner, runDirectory)));
            }

            for (Future<BackupServiceResult> future : futures) {
                try {
                    BackupServiceResult outcome = future.get();
                    serviceRunResults.add(outcome);
                    if (COMPLETED.equals(outcome.status())) {
                        LOGGER.info("Runner succeeded: {}", outcome.serviceName());
                    } else {
                        LOGGER.warn("Runner failed (non-fatal): {}", outcome.serviceName());
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Backup run interrupted", ex);
                } catch (ExecutionException ex) {
                    errorMessage.concat(ex.getMessage() + "\n");
                    LOGGER.warn("Unexpected failure while waiting for runner task", ex.getCause());
                }
            }

            int successfulRunnerCount = (int) serviceRunResults.stream().filter(r -> COMPLETED.equals(r.status())).count();
            LOGGER.info(
                "All runners finished: succeeded={}/{}",
                successfulRunnerCount,
                runners.size()
            );
            BackupArchive archive = archiveService.createArchive(runDirectory);
            for (BackupServiceResult serviceResult : serviceRunResults) {
                serviceResult.setId(archive.id());
            }
            long durationMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli();
            LOGGER.info(
                "Backup run complete: archive={}, size={}B, duration={}ms, services={}",
                archive.fileName(),
                archive.sizeBytes(),
                durationMs,
                serviceRunResults.stream().map(BackupServiceResult::serviceName).toList()
            );

            String status;
            if (successfulRunnerCount == runners.size()) {
                status = COMPLETED;
            } else if (successfulRunnerCount > 0) {
                status = PARTIAL;
            } else {
                status = FAILED;
            }
            BackupRunResult backupRecord = new BackupRunResult(
                archive.id(),
                status,
                formatTime(startedAt),
                recordCurrentTime(),
                serviceRunResults,
                archive,
                errorMessage
            );
            db.addNewBackupRecord(backupRecord);
            return backupRecord;
        } finally {
            archiveService.removeRunDirectory(runDirectory);
            running.set(false);
            LOGGER.debug("Run directory cleaned up: {}", runDirectory);
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

    private BackupServiceResult runSingleRunner(Runner runner, Path runDirectory) {
        LOGGER.info("[{}] Runner starting", runner.getServiceName());
        long start = System.currentTimeMillis();
        try {
            runner.run(runDirectory);
            LOGGER.info("[{}] Runner finished in {}ms", runner.getServiceName(), System.currentTimeMillis() - start);
        } catch (RuntimeException ex) {
            LOGGER.error("[{}] Runner failed after {}ms, continuing with remaining services",
                runner.getServiceName(), System.currentTimeMillis() - start, ex);
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
}
