package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.formatTime;
import static wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.recordCurrentTime;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.PARTIAL;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import wellatleastitried.mediagarrdServer.repository.BackupRecordRepository;
import wellatleastitried.mediagarrdServer.repository.ServiceRepository;

@Service
public class BackupOrchestratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupOrchestratorService.class);

    private static final String SNAPSHOT_NAME = DatabaseSnapshotService.SERVICE_NAME;
    private static final int RUNNER_POOL_SIZE = 6;
    private static final long RUNNER_IDLE_TTL_SECONDS = 30;
    private static final long SCHEDULED_CHECK_INTERVAL_MS = 30000;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final ServiceBackupRunner runner;
    private final BackupArchiveService archiveService;
    private final BackupScheduleService scheduleService;
    private final DatabaseSnapshotService snapshotService;
    private final ServiceRepository services;
    private final BackupRecordRepository backups;
    private final BackupRunLifecycle lifecycle;
    private final WebhookService webhook;
    private final ExecutorService runnerExecutor = createRunnerExecutor();

    public BackupOrchestratorService(
        ServiceBackupRunner runner,
        BackupArchiveService archiveService,
        BackupScheduleService scheduleService,
        DatabaseSnapshotService snapshotService,
        ServiceRepository services,
        BackupRecordRepository backups,
        BackupRunLifecycle lifecycle,
        WebhookService webhook
    ) {
        this.runner = runner;
        this.archiveService = archiveService;
        this.scheduleService = scheduleService;
        this.snapshotService = snapshotService;
        this.services = services;
        this.backups = backups;
        this.lifecycle = lifecycle;
        this.webhook = webhook;
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
        if (lifecycle.isRunning()) {
            LOGGER.debug("Scheduled check skipped, backup already in progress");
            return;
        }
        if (!scheduleService.dueNow()) {
            return;
        }
        LOGGER.info("Scheduled backup triggered, next run at {}", scheduleService.getNextRun());
        try {
            runBackup(true);
        } catch (RunRejectedException ex) {
            LOGGER.warn("Scheduled backup skipped: {}", ex.getMessage());
        } catch (RuntimeException ex) {
            LOGGER.error("Scheduled backup run failed", ex);
        }
    }

    public BackupRunResult runBackup() {
        return runBackup(false);
    }

    BackupRunResult runBackup(boolean scheduled) {
        Instant startedAt = Instant.now();
        return lifecycle.run(startedAt, run -> execute(run, startedAt, scheduled));
    }

    private BackupRunResult execute(BackupRunLifecycle.Run run, Instant startedAt, boolean scheduled) {
        Path runDirectory = null;
        boolean started = false;
        boolean notified = false;
        try {
            List<ServiceDefinition> configured = services.fetchServices();
            if (configured.isEmpty()) {
                LOGGER.warn("No services are configured, aborting backup run before creating a run directory");
                if (scheduled) {
                    scheduleService.deferNextRun();
                }
                throw new RunRejectedException("No services are configured for backup");
            }
            run.markStarted();
            started = true;

            scheduleService.markRunStarted();
            runDirectory = archiveService.createRunDirectory();
            final Path runDir = runDirectory;

            LOGGER.info("Backup run started: runDir={}, services={}",
                runDir, configured.stream().map(ServiceDefinition::name).toList());

            DatabaseSnapshotService.Outcome snapshot = snapshotDatabase(runDir);
            BackupServiceResult snapshotResult = snapshot.result();
            Set<String> excluded = COMPLETED.equals(snapshotResult.status()) && snapshot.hostPath() != null
                ? DatabaseSnapshotService.reservedNames(snapshot.hostPath())
                : Set.of();

            List<BackupServiceResult> serviceRunResults = copyServices(configured, runDir, excluded);

            List<BackupServiceResult> incomplete = serviceRunResults.stream()
                .filter(r -> !COMPLETED.equals(r.status()))
                .toList();
            int succeeded = configured.size() - incomplete.size();
            LOGGER.info("All runners finished: succeeded={}/{}", succeeded, configured.size());
            incomplete.forEach(r -> LOGGER.warn("Runner did not complete (non-fatal): {} ({})", r.serviceName(), r.status()));

            String errorMessage = incomplete.stream()
                .map(r -> r.serviceName() + ": " + Objects.requireNonNullElse(r.errorMessage(), "no details"))
                .collect(Collectors.joining("\n"));

            if (succeeded == 0 && incomplete.stream().allMatch(r -> FAILED.equals(r.status()))) {
                String runId = "failed-" + UUID.randomUUID();
                serviceRunResults.forEach(r -> r.setId(runId));
                BackupRunResult failedRecord = new BackupRunResult(
                    runId, formatTime(startedAt), recordCurrentTime(), FAILED, serviceRunResults, null, errorMessage);
                backups.addNewBackupRecord(failedRecord);
                LOGGER.error("Every service failed, no archive created (run {}):\n{}", runId, errorMessage);
                notified = true;
                webhook.notifyRun(failedRecord);
                throw new BackupFailedException("Every service failed, no archive created: " + errorMessage);
            }

            List<BackupServiceResult> recordedResults = new ArrayList<>(serviceRunResults);
            recordedResults.add(snapshotResult);
            boolean snapshotFailed = !COMPLETED.equals(snapshotResult.status());
            if (snapshotFailed) {
                LOGGER.warn("Database snapshot did not complete (non-fatal): {}", snapshotResult.errorMessage());
                String snapshotError = SNAPSHOT_NAME + ": " + Objects.requireNonNullElse(snapshotResult.errorMessage(), "no details");
                errorMessage = errorMessage.isEmpty() ? snapshotError : errorMessage + "\n" + snapshotError;
            }

            BackupArchive archive = archiveService.createArchive(runDirectory);
            recordedResults.forEach(r -> r.setId(archive.id()));

            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            LOGGER.info("Backup run complete: archive={}, size={}B, duration={}ms, services={}",
                archive.fileName(), archive.sizeBytes(), durationMs,
                recordedResults.stream().map(BackupServiceResult::serviceName).toList());

            String status = succeeded == configured.size() ? COMPLETED : succeeded > 0 ? PARTIAL : FAILED;
            if (snapshotFailed && COMPLETED.equals(status)) {
                status = PARTIAL;
            }

            BackupRunResult backupRecord = new BackupRunResult(
                archive.id(), formatTime(startedAt), recordCurrentTime(), status,
                recordedResults, archive, errorMessage);
            backups.addNewBackupRecord(backupRecord);
            notified = true;
            webhook.notifyRun(backupRecord);
            return backupRecord;
        } catch (RuntimeException e) {
            if (started && !notified) {
                webhook.notifyFailure(e.getMessage());
            }
            throw e;
        } finally {
            if (runDirectory != null) {
                try {
                    archiveService.removeRunDirectory(runDirectory);
                    LOGGER.debug("Run directory cleaned up: {}", runDirectory);
                } catch (RuntimeException e) {
                    LOGGER.error("Failed to clean up run directory {}", runDirectory, e);
                }
            }
        }
    }

    private List<BackupServiceResult> copyServices(List<ServiceDefinition> services, Path runDir, Set<String> excluded) {
        Map<String, ServiceBackupRunner.Tally> tallies = new LinkedHashMap<>();
        List<PathCoverage.Item> unresolved = new ArrayList<>();
        for (ServiceDefinition service : services) {
            tallies.put(service.name(), new ServiceBackupRunner.Tally(service.name()));
            for (int i = 0; i < service.paths().size(); i++) {
                unresolved.add(new PathCoverage.Item(service, i));
            }
        }

        int round = 0;
        while (!unresolved.isEmpty()) {
            round++;
            List<PathCoverage.Owner> owners = PathCoverage.plan(unresolved);
            Map<String, List<PathCoverage.Owner>> byService = new LinkedHashMap<>();
            owners.forEach(owner -> byService.computeIfAbsent(owner.item().service().name(), name -> new ArrayList<>()).add(owner));
            LOGGER.debug("Copy round {}: {} path(s), {} left", round, owners.size(), unresolved.size());

            Map<String, CompletableFuture<ServiceBackupRunner.ServiceRound>> futures = new LinkedHashMap<>();
            byService.forEach((name, serviceOwners) -> {
                ServiceDefinition service = serviceOwners.get(0).item().service();
                List<Integer> indexes = serviceOwners.stream().map(owner -> owner.item().index()).toList();
                List<Set<String>> covered = serviceOwners.stream()
                    .map(owner -> owner.covered().stream().map(PathCoverage.Item::key).collect(Collectors.toUnmodifiableSet()))
                    .toList();
                futures.put(name, CompletableFuture
                    .supplyAsync(() -> runner.copyPaths(service, indexes, runDir, excluded, covered), runnerExecutor)
                    .exceptionally(t -> {
                        Throwable cause = unwrap(t);
                        LOGGER.warn("Runner {} failed unexpectedly", name, cause);
                        return failedRound(service, indexes, cause);
                    }));
            });

            Map<PathCoverage.Item, ServiceBackupRunner.PathCopy> copies = new HashMap<>();
            futures.forEach((name, future) -> {
                ServiceBackupRunner.ServiceRound finished = future.join();
                tallies.get(name).add(finished);
                ServiceDefinition service = byService.get(name).get(0).item().service();
                finished.paths().forEach(copy -> copies.put(new PathCoverage.Item(service, copy.index()), copy));
            });

            List<PathCoverage.Item> resolved = new ArrayList<>();
            for (PathCoverage.Owner owner : owners) {
                resolved.add(owner.item());
                ServiceBackupRunner.PathCopy copy = copies.get(owner.item());
                if (copy == null || !copy.copied()) {
                    owner.covered().forEach(item -> LOGGER.info("[{}] {} was covered by {} which failed, copying it on its own",
                        item.service().name(), item.path(), owner.item().path()));
                    continue;
                }
                for (PathCoverage.Item item : owner.covered()) {
                    if (copy.confirmed().contains(item.key())) {
                        tallies.get(item.service().name()).addCovered(1);
                        resolved.add(item);
                        LOGGER.info("[{}] {} is included in {} ({})", item.service().name(), item.path(), owner.item().path(), owner.item().service().name());
                    } else {
                        LOGGER.info("[{}] {} was not found inside {}, copying it on its own", item.service().name(), item.path(), owner.item().path());
                    }
                }
            }
            unresolved.removeAll(resolved);
        }
        return tallies.values().stream().map(ServiceBackupRunner.Tally::result).toList();
    }

    private static ServiceBackupRunner.ServiceRound failedRound(ServiceDefinition service, List<Integer> indexes, Throwable cause) {
        List<ServiceBackupRunner.PathCopy> copies = indexes.stream()
            .map(index -> new ServiceBackupRunner.PathCopy(index, service.paths().get(index), false, null, Set.of()))
            .toList();
        return new ServiceBackupRunner.ServiceRound(Objects.requireNonNullElse(cause.getMessage(), cause.toString()), copies);
    }

    private DatabaseSnapshotService.Outcome snapshotDatabase(Path runDir) {
        try {
            return snapshotService.snapshotWithHostPath(runDir);
        } catch (RuntimeException e) {
            LOGGER.warn("[{}] Failed to snapshot database", SNAPSHOT_NAME, e);
            return new DatabaseSnapshotService.Outcome(failedResult(SNAPSHOT_NAME, e), null);
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

    public void servicesChanged() {
        scheduleService.releaseDeferral();
    }

    public boolean isRunning() {
        return lifecycle.isRunning();
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
}
