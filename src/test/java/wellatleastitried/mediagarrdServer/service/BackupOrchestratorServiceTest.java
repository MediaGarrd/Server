package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.PARTIAL;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;
import wellatleastitried.mediagarrdServer.repository.BackupRecordRepository;
import wellatleastitried.mediagarrdServer.repository.ServiceRepository;
import wellatleastitried.mediagarrdServer.repository.SettingsRepository;

@Timeout(30)
class BackupOrchestratorServiceTest {

    private static final ServiceDefinition RADARR = new ServiceDefinition("radarr", List.of("/srv/radarr"));
    private static final ServiceDefinition SONARR = new ServiceDefinition("sonarr", List.of("/srv/sonarr"));
    private static final String SNAPSHOT = DatabaseSnapshotService.SERVICE_NAME;
    private static final Path RUN_DIR = Path.of("/tmp/run");
    private static final BackupArchive ARCHIVE = new BackupArchive("archive-1", "archive-1.zip", Path.of("archive-1.zip"), 1, Instant.EPOCH);

    private final ServiceBackupRunner runner = mock(ServiceBackupRunner.class);
    private final BackupArchiveService archives = mock(BackupArchiveService.class);
    private final BackupScheduleService schedule = mock(BackupScheduleService.class);
    private final ServiceRepository services = mock(ServiceRepository.class);
    private final BackupRecordRepository backups = mock(BackupRecordRepository.class);
    private final DatabaseSnapshotService snapshots = mock(DatabaseSnapshotService.class);
    private final RunStatusBroadcaster broadcaster = mock(RunStatusBroadcaster.class);
    private final BackupRunLifecycle lifecycle = new BackupRunLifecycle(broadcaster);
    private final WebhookService webhook = mock(WebhookService.class);
    private final BackupOrchestratorService orchestrator = new BackupOrchestratorService(runner, archives, schedule, snapshots, services, backups, lifecycle, webhook);

    @BeforeEach
    void setUp() {
        when(archives.createRunDirectory()).thenReturn(RUN_DIR);
        when(archives.createArchive(RUN_DIR)).thenReturn(ARCHIVE);
        when(snapshots.snapshotWithHostPath(RUN_DIR)).thenReturn(snapshotOutcome(COMPLETED));
    }

    @AfterEach
    void tearDown() {
        orchestrator.shutdownRunnerExecutor();
    }

    @Test
    void refusesToRunWithoutServices() {
        when(services.fetchServices()).thenReturn(List.of());

        assertThrows(RunRejectedException.class, orchestrator::runBackup);

        verify(archives, never()).createRunDirectory();
        assertFalse(orchestrator.isRunning());
        verify(broadcaster, never()).publishStarted(any());
        verify(broadcaster, never()).publishFinished(any(), any(), any());
        verify(schedule, never()).deferNextRun();
        verify(webhook, never()).notifyRun(any());
        verify(webhook, never()).notifyFailure(any());
    }

    @Test
    void aRejectedManualRunLeavesTheRealScheduleUntouched() {
        MediaGarrdProperties properties = new MediaGarrdProperties();
        BackupScheduleService realSchedule = new BackupScheduleService(properties, mock(SettingsRepository.class), mock(BackupRecordRepository.class), mock(SetupService.class));
        BackupOrchestratorService manual = new BackupOrchestratorService(runner, archives, realSchedule, snapshots, services, backups, new BackupRunLifecycle(broadcaster), webhook);
        when(services.fetchServices()).thenReturn(List.of());
        Instant before = realSchedule.getNextRun();

        assertThrows(RunRejectedException.class, manual::runBackup);

        assertEquals(before, realSchedule.getNextRun());
        manual.shutdownRunnerExecutor();
    }

    @Test
    void aScheduledRunWithoutServicesDefersTheNextCheck() {
        when(schedule.dueNow()).thenReturn(true);
        when(services.fetchServices()).thenReturn(List.of());

        orchestrator.scheduledRun();

        verify(schedule).deferNextRun();
        verify(broadcaster, never()).publishStarted(any());
    }

    @Test
    void aScheduledRunRejectedBecauseOneIsInProgressDoesNotDefer() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(services.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.copyPaths(eq(RADARR), any(), any(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return round(RADARR, COMPLETED);
        });
        when(schedule.dueNow()).thenReturn(true);

        CompletableFuture<BackupRunResult> manual = CompletableFuture.supplyAsync(orchestrator::runBackup);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(RunRejectedException.class, () -> orchestrator.runBackup(true));

        verify(schedule, never()).deferNextRun();
        release.countDown();
        manual.get(5, TimeUnit.SECONDS);
    }

    @Test
    void aDueScheduledRunWithServicesRunsABackup() {
        when(schedule.dueNow()).thenReturn(true);
        when(services.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.copyPaths(eq(RADARR), any(), eq(RUN_DIR), any(), any())).thenReturn(round(RADARR, COMPLETED));

        orchestrator.scheduledRun();

        verify(schedule).markRunStarted();
        verify(archives).createArchive(RUN_DIR);
        verify(broadcaster).publishFinished(eq(COMPLETED), any(), any());
    }

    @Test
    void aScheduledCheckThatIsNotDueDoesNothing() {
        when(schedule.dueNow()).thenReturn(false);

        orchestrator.scheduledRun();

        verify(services, never()).fetchServices();
        verify(broadcaster, never()).publishStarted(any());
        verify(schedule, never()).markRunStarted();
        verify(schedule, never()).deferNextRun();
    }

    @Test
    void aScheduledCheckDuringARunSkipsWithoutLookingAtTheSchedule() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(services.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.copyPaths(eq(RADARR), any(), any(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return round(RADARR, COMPLETED);
        });
        CompletableFuture<BackupRunResult> manual = CompletableFuture.supplyAsync(orchestrator::runBackup);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        orchestrator.scheduledRun();

        verify(schedule, never()).dueNow();
        verify(broadcaster, org.mockito.Mockito.times(1)).publishStarted(any());
        release.countDown();
        manual.get(5, TimeUnit.SECONDS);
    }

    @Test
    void aRunRejectedBecauseOneIsInProgressSendsNoWebhook() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(services.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.copyPaths(eq(RADARR), any(), any(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return round(RADARR, COMPLETED);
        });
        CompletableFuture<BackupRunResult> manual = CompletableFuture.supplyAsync(orchestrator::runBackup);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertThrows(RunRejectedException.class, orchestrator::runBackup);

        release.countDown();
        manual.get(5, TimeUnit.SECONDS);
        verify(webhook, never()).notifyFailure(any());
        verify(webhook, org.mockito.Mockito.times(1)).notifyRun(any());
    }

    @Test
    void aFailureBeforeTheRunStartsSendsNoWebhook() {
        when(services.fetchServices()).thenThrow(new IllegalStateException("database locked"));

        assertThrows(IllegalStateException.class, orchestrator::runBackup);

        verify(webhook, never()).notifyFailure(any());
        verify(webhook, never()).notifyRun(any());
    }

    @Test
    void aFailureAfterTheRunStartsSendsAFailedWebhookAndRethrows() {
        when(services.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.copyPaths(eq(RADARR), any(), eq(RUN_DIR), any(), any())).thenReturn(round(RADARR, COMPLETED));
        when(archives.createArchive(RUN_DIR)).thenThrow(new IllegalStateException("disk full"));

        IllegalStateException e = assertThrows(IllegalStateException.class, orchestrator::runBackup);

        assertEquals("disk full", e.getMessage());
        verify(webhook).notifyFailure("disk full");
        verify(webhook, never()).notifyRun(any());
    }

    @Test
    void aScheduledRunThatEndsPartialSendsAWebhook() {
        when(schedule.dueNow()).thenReturn(true);
        givenResults(COMPLETED, FAILED);

        orchestrator.scheduledRun();

        ArgumentCaptor<BackupRunResult> sent = ArgumentCaptor.forClass(BackupRunResult.class);
        verify(webhook).notifyRun(sent.capture());
        assertEquals(PARTIAL, sent.getValue().status());
    }

    @Test
    void aCompletedRunStillHandsTheResultToTheWebhookService() {
        givenResults(COMPLETED, COMPLETED);

        BackupRunResult result = orchestrator.runBackup();

        verify(webhook).notifyRun(result);
        verify(webhook, never()).notifyFailure(any());
    }

    @Test
    void aRunWhereEveryServiceFailsSendsOneFailedWebhook() {
        givenResults(FAILED, FAILED);

        assertThrows(IllegalStateException.class, orchestrator::runBackup);

        ArgumentCaptor<BackupRunResult> sent = ArgumentCaptor.forClass(BackupRunResult.class);
        verify(webhook).notifyRun(sent.capture());
        assertEquals(FAILED, sent.getValue().status());
        assertNull(sent.getValue().archive());
        verify(webhook, never()).notifyFailure(any());
    }

    @Test
    void aFailedRecordWithAnArchiveSendsAFailedWebhook() {
        givenResults(PARTIAL, FAILED);

        orchestrator.runBackup();

        ArgumentCaptor<BackupRunResult> sent = ArgumentCaptor.forClass(BackupRunResult.class);
        verify(webhook).notifyRun(sent.capture());
        assertEquals(FAILED, sent.getValue().status());
        assertEquals(ARCHIVE, sent.getValue().archive());
    }

    @Test
    void aScheduledRunThatFailsDoesNotStopTheScheduler() {
        when(schedule.dueNow()).thenReturn(true);
        when(services.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.copyPaths(eq(RADARR), any(), eq(RUN_DIR), any(), any())).thenThrow(new IllegalStateException("crashed"));
        when(archives.createArchive(RUN_DIR)).thenThrow(new IllegalStateException("disk full"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(orchestrator::scheduledRun);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(orchestrator::scheduledRun);

        verify(broadcaster, org.mockito.Mockito.times(2)).publishFinished(eq(FAILED), any(), any());
        assertFalse(orchestrator.isRunning());
    }

    @Test
    void aScheduledRunWhoseArchivingFailsDoesNotStopTheScheduler() {
        when(schedule.dueNow()).thenReturn(true);
        when(services.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.copyPaths(eq(RADARR), any(), eq(RUN_DIR), any(), any())).thenReturn(round(RADARR, COMPLETED));
        when(archives.createArchive(RUN_DIR)).thenThrow(new IllegalStateException("disk full"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(orchestrator::scheduledRun);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(orchestrator::scheduledRun);

        verify(archives, org.mockito.Mockito.times(2)).createArchive(RUN_DIR);
        verify(broadcaster, org.mockito.Mockito.times(2)).publishFinished(eq(FAILED), any(), any());
        assertFalse(orchestrator.isRunning());
    }

    @Test
    void changingServicesReleasesTheDeferral() {
        orchestrator.servicesChanged();

        verify(schedule).releaseDeferral();
    }

    @Test
    void completedWhenEveryServiceSucceeds() {
        givenResults(COMPLETED, COMPLETED);

        BackupRunResult result = orchestrator.runBackup();

        assertEquals(COMPLETED, result.status());
        assertEquals("", result.errorMessage());
        assertEquals(List.of("radarr", "sonarr", SNAPSHOT), result.serviceNames());
        result.serviceResults().forEach(r -> assertEquals("archive-1", r.runId()));
        verify(schedule).markRunStarted();
        verify(backups).addNewBackupRecord(result);
        verify(archives).removeRunDirectory(RUN_DIR);
    }

    @Test
    void partialWhenSomeServicesFail() {
        givenResults(COMPLETED, FAILED);

        BackupRunResult result = orchestrator.runBackup();

        assertEquals(PARTIAL, result.status());
        assertEquals("sonarr: boom", result.errorMessage());
        verify(broadcaster).publishFinished(eq(PARTIAL), any(), any());
    }

    @Test
    void partialWhenOnlyTheDatabaseSnapshotFails() {
        givenResults(COMPLETED, COMPLETED);
        when(snapshots.snapshotWithHostPath(RUN_DIR)).thenReturn(snapshotOutcome(FAILED));

        BackupRunResult result = orchestrator.runBackup();

        assertEquals(PARTIAL, result.status());
        assertEquals(SNAPSHOT + ": boom", result.errorMessage());
        assertEquals(ARCHIVE, result.archive());
        result.serviceResults().forEach(r -> assertEquals("archive-1", r.runId()));
        verify(backups).addNewBackupRecord(result);
    }

    @Test
    void snapshotFailureIsAddedToTheServiceErrors() {
        givenResults(COMPLETED, FAILED);
        when(snapshots.snapshotWithHostPath(RUN_DIR)).thenReturn(snapshotOutcome(FAILED));

        BackupRunResult result = orchestrator.runBackup();

        assertEquals(PARTIAL, result.status());
        assertEquals("sonarr: boom\n" + SNAPSHOT + ": boom", result.errorMessage());
    }

    @Test
    void aThrowingSnapshotStillProducesAPartialArchive() {
        givenResults(COMPLETED, COMPLETED);
        when(snapshots.snapshotWithHostPath(RUN_DIR)).thenThrow(new IllegalStateException("docker down"));

        BackupRunResult result = orchestrator.runBackup();

        assertEquals(PARTIAL, result.status());
        assertEquals(SNAPSHOT + ": docker down", result.errorMessage());
        assertEquals(ARCHIVE, result.archive());
        verify(backups).addNewBackupRecord(result);
    }

    @Test
    void snapshotRunsBeforeTheCopiesAndTheArchiveIsCreatedLast() {
        givenResults(COMPLETED, COMPLETED);

        orchestrator.runBackup();

        var order = inOrder(snapshots, runner, archives);
        order.verify(snapshots).snapshotWithHostPath(RUN_DIR);
        order.verify(runner, org.mockito.Mockito.atLeastOnce()).copyPaths(any(), any(), eq(RUN_DIR), any(), any());
        order.verify(archives).createArchive(RUN_DIR);
    }

    @Test
    void failedWhenNoServiceCompletes() {
        givenResults(PARTIAL, FAILED);

        assertEquals(FAILED, orchestrator.runBackup().status());
    }

    @Test
    void skipsArchiveWhenEveryServiceFails() {
        givenResults(FAILED, FAILED);

        IllegalStateException e = assertThrows(IllegalStateException.class, orchestrator::runBackup);

        assertTrue(e.getMessage().contains("radarr: boom\nsonarr: boom"));
        verify(archives, never()).createArchive(any());
        verify(archives).removeRunDirectory(RUN_DIR);
        assertFalse(orchestrator.isRunning());

        ArgumentCaptor<BackupRunResult> recorded = ArgumentCaptor.forClass(BackupRunResult.class);
        verify(backups).addNewBackupRecord(recorded.capture());
        BackupRunResult run = recorded.getValue();
        assertEquals(FAILED, run.status());
        assertNull(run.archive());
        assertEquals("radarr: boom\nsonarr: boom", run.errorMessage());
        run.serviceResults().forEach(r -> assertEquals(run.runId(), r.runId()));
        verify(broadcaster).publishFinished(eq(FAILED), any(), any());
    }

    @Test
    void runnerExceptionsBecomeFailedResults() {
        when(services.fetchServices()).thenReturn(List.of(RADARR, SONARR));
        when(runner.copyPaths(eq(RADARR), any(), eq(RUN_DIR), any(), any())).thenThrow(new IllegalStateException("crashed"));
        when(runner.copyPaths(eq(SONARR), any(), eq(RUN_DIR), any(), any())).thenReturn(round(SONARR, COMPLETED));

        BackupRunResult result = orchestrator.runBackup();

        assertEquals(PARTIAL, result.status());
        assertEquals("radarr: crashed", result.errorMessage());
    }

    @Test
    void cleansUpWhenArchivingFails() {
        givenResults(COMPLETED, COMPLETED);
        when(archives.createArchive(RUN_DIR)).thenThrow(new IllegalStateException("disk full"));

        assertThrows(IllegalStateException.class, orchestrator::runBackup);

        verify(archives).removeRunDirectory(RUN_DIR);
        verify(backups, never()).addNewBackupRecord(any());
        assertFalse(orchestrator.isRunning());
        verify(broadcaster).publishFinished(eq(FAILED), any(), any());
    }

    @Test
    void rejectsConcurrentRuns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(services.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.copyPaths(eq(RADARR), any(), any(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return round(RADARR, COMPLETED);
        });

        CompletableFuture<BackupRunResult> first = CompletableFuture.supplyAsync(orchestrator::runBackup);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertTrue(orchestrator.isRunning());
        assertThrows(RunRejectedException.class, orchestrator::runBackup);

        release.countDown();
        assertEquals(COMPLETED, first.get(5, TimeUnit.SECONDS).status());
        assertFalse(orchestrator.isRunning());
    }

    @Test
    void configChangesAreRejectedDuringABackupRun() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(services.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.copyPaths(eq(RADARR), any(), any(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return round(RADARR, COMPLETED);
        });

        CompletableFuture<BackupRunResult> run = CompletableFuture.supplyAsync(orchestrator::runBackup);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertThrows(BackupInProgressException.class, () -> lifecycle.whileIdle(() -> "changed"));

        release.countDown();
        run.get(5, TimeUnit.SECONDS);
        assertEquals("changed", lifecycle.whileIdle(() -> "changed"));
    }

    @Test
    void backupWaitsForInFlightConfigChange() throws Exception {
        CountDownLatch changing = new CountDownLatch(1);
        CountDownLatch finishChange = new CountDownLatch(1);
        givenResults(COMPLETED, COMPLETED);

        CompletableFuture<String> change = CompletableFuture.supplyAsync(() -> lifecycle.whileIdle(() -> {
            changing.countDown();
            try {
                finishChange.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "changed";
        }));
        assertTrue(changing.await(5, TimeUnit.SECONDS));

        CompletableFuture<BackupRunResult> run = CompletableFuture.supplyAsync(orchestrator::runBackup);
        Thread.sleep(100);
        assertFalse(run.isDone());
        verify(services, never()).fetchServices();

        finishChange.countDown();
        assertEquals("changed", change.get(5, TimeUnit.SECONDS));
        assertEquals(COMPLETED, run.get(5, TimeUnit.SECONDS).status());
    }

    @Test
    void publishesStartedBeforeWorkAndFinishedWithTheRunStatus() {
        givenResults(COMPLETED, COMPLETED);

        orchestrator.runBackup();

        var order = inOrder(broadcaster, runner, services);
        order.verify(services).fetchServices();
        order.verify(broadcaster).publishStarted(any());
        order.verify(runner, org.mockito.Mockito.atLeastOnce()).copyPaths(any(), any(), any(), any(), any());
        order.verify(broadcaster).publishFinished(eq(COMPLETED), any(), any());
        verify(broadcaster, org.mockito.Mockito.times(1)).publishStarted(any());
        verify(broadcaster, org.mockito.Mockito.times(1)).publishFinished(any(), any(), any());
    }

    @Test
    void publishesFailedWhenTheRunDirectoryCannotBeCreated() {
        when(services.fetchServices()).thenReturn(List.of(RADARR));
        when(archives.createRunDirectory()).thenThrow(new IllegalStateException("no space"));

        assertThrows(IllegalStateException.class, orchestrator::runBackup);

        verify(broadcaster).publishStarted(any());
        verify(broadcaster).publishFinished(eq(FAILED), any(), any());
        assertFalse(orchestrator.isRunning());
    }

    @Test
    void publishesFailedWhenRecordingTheRunThrows() {
        givenResults(COMPLETED, COMPLETED);
        doThrow(new IllegalStateException("db locked")).when(backups).addNewBackupRecord(any());

        assertThrows(IllegalStateException.class, orchestrator::runBackup);

        verify(broadcaster).publishFinished(eq(FAILED), any(), any());
        assertFalse(orchestrator.isRunning());
    }

    @Test
    void runningIsFalseWhenFinishedIsPublished() {
        givenResults(COMPLETED, COMPLETED);
        AtomicBoolean runningAtFinish = new AtomicBoolean(true);
        doAnswer(invocation -> {
            runningAtFinish.set(orchestrator.isRunning());
            return null;
        }).when(broadcaster).publishFinished(any(), any(), any());

        orchestrator.runBackup();

        assertFalse(runningAtFinish.get());
    }

    @Test
    void aFailingCleanupDoesNotStickTheRunningFlag() {
        givenResults(COMPLETED, COMPLETED);
        doThrow(new IllegalStateException("cannot delete")).when(archives).removeRunDirectory(RUN_DIR);

        BackupRunResult result = orchestrator.runBackup();

        assertEquals(COMPLETED, result.status());
        assertFalse(orchestrator.isRunning());
        verify(broadcaster).publishFinished(eq(COMPLETED), any(), any());
        assertEquals("changed", lifecycle.whileIdle(() -> "changed"));
        assertEquals(COMPLETED, orchestrator.runBackup().status());
    }

    @Test
    void aFailingCleanupDoesNotHideTheRunsOwnFailure() {
        givenResults(COMPLETED, COMPLETED);
        when(archives.createArchive(RUN_DIR)).thenThrow(new IllegalStateException("disk full"));
        doThrow(new IllegalStateException("cannot delete")).when(archives).removeRunDirectory(RUN_DIR);

        IllegalStateException e = assertThrows(IllegalStateException.class, orchestrator::runBackup);

        assertEquals("disk full", e.getMessage());
        assertFalse(orchestrator.isRunning());
    }

    @Test
    void aFailingPublishNeverBreaksTheRun() {
        givenResults(COMPLETED, COMPLETED);
        doThrow(new IllegalStateException("boom")).when(broadcaster).publishStarted(any());
        doThrow(new IllegalStateException("boom")).when(broadcaster).publishFinished(any(), any(), any());

        assertEquals(COMPLETED, orchestrator.runBackup().status());
        assertFalse(orchestrator.isRunning());
    }

    @Test
    void aRejectedConcurrentRunPublishesNothingExtra() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(services.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.copyPaths(eq(RADARR), any(), any(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return round(RADARR, COMPLETED);
        });

        CompletableFuture<BackupRunResult> first = CompletableFuture.supplyAsync(orchestrator::runBackup);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(RunRejectedException.class, orchestrator::runBackup);
        verify(broadcaster, org.mockito.Mockito.times(1)).publishStarted(any());
        verify(broadcaster, never()).publishFinished(any(), any(), any());

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        verify(broadcaster, org.mockito.Mockito.times(1)).publishFinished(eq(COMPLETED), any(), any());
    }

    @Test
    void aFinishIsNeverOvertakenByTheNextRunsStart() throws Exception {
        givenResults(COMPLETED, COMPLETED);
        CountDownLatch inFinish = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            inFinish.countDown();
            release.await();
            return null;
        }).doNothing().when(broadcaster).publishFinished(any(), any(), any());

        CompletableFuture<BackupRunResult> first = CompletableFuture.supplyAsync(orchestrator::runBackup);
        assertTrue(inFinish.await(5, TimeUnit.SECONDS));
        CompletableFuture<BackupRunResult> second = CompletableFuture.supplyAsync(orchestrator::runBackup);
        Thread.sleep(200);

        assertFalse(second.isDone());
        verify(broadcaster, org.mockito.Mockito.times(1)).publishStarted(any());

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        var order = inOrder(broadcaster);
        order.verify(broadcaster).publishStarted(any());
        order.verify(broadcaster).publishFinished(any(), any(), any());
        order.verify(broadcaster).publishStarted(any());
        order.verify(broadcaster).publishFinished(any(), any(), any());
    }

    private void givenResults(String radarrStatus, String sonarrStatus) {
        when(services.fetchServices()).thenReturn(List.of(RADARR, SONARR));
        when(runner.copyPaths(eq(RADARR), any(), eq(RUN_DIR), any(), any())).thenReturn(round(RADARR, radarrStatus));
        when(runner.copyPaths(eq(SONARR), any(), eq(RUN_DIR), any(), any())).thenReturn(round(SONARR, sonarrStatus));
    }

    private static DatabaseSnapshotService.Outcome snapshotOutcome(String status) {
        return new DatabaseSnapshotService.Outcome(result(SNAPSHOT, status), Path.of("/srv/mediagarrd/db/mediagarrd.db"));
    }

    private static ServiceBackupRunner.ServiceRound round(ServiceDefinition service, String status) {
        String path = service.paths().get(0);
        return switch (status) {
            case COMPLETED -> new ServiceBackupRunner.ServiceRound(null, List.of(new ServiceBackupRunner.PathCopy(0, path, true, null, Set.of())));
            case PARTIAL -> new ServiceBackupRunner.ServiceRound(null, List.of(
                new ServiceBackupRunner.PathCopy(0, path, true, null, Set.of()), new ServiceBackupRunner.PathCopy(1, path, false, "boom", Set.of())));
            default -> new ServiceBackupRunner.ServiceRound(null, List.of(new ServiceBackupRunner.PathCopy(0, path, false, "boom", Set.of())));
        };
    }

    private static BackupServiceResult result(String name, String status) {
        return new BackupServiceResult(null, status, "start", "end", name, COMPLETED.equals(status) ? null : "boom");
    }
}
