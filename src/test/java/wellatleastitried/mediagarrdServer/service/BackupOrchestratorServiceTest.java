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
    private final DatabaseService db = mock(DatabaseService.class);
    private final DatabaseSnapshotService snapshots = mock(DatabaseSnapshotService.class);
    private final RunStatusBroadcaster broadcaster = mock(RunStatusBroadcaster.class);
    private final BackupOrchestratorService orchestrator = new BackupOrchestratorService(runner, archives, schedule, snapshots, db, broadcaster);

    @BeforeEach
    void setUp() {
        when(archives.createRunDirectory()).thenReturn(RUN_DIR);
        when(archives.createArchive(RUN_DIR)).thenReturn(ARCHIVE);
        when(snapshots.snapshot(RUN_DIR)).thenReturn(snapshotResult(COMPLETED));
    }

    @AfterEach
    void tearDown() {
        orchestrator.shutdownRunnerExecutor();
    }

    @Test
    void refusesToRunWithoutServices() {
        when(db.fetchServices()).thenReturn(List.of());

        assertThrows(BackupOrchestratorService.RunRejectedException.class, orchestrator::runBackup);

        verify(archives, never()).createRunDirectory();
        assertFalse(orchestrator.isRunning());
        verify(broadcaster, never()).publishStarted(any());
        verify(broadcaster, never()).publishFinished(any(), any(), any());
        verify(schedule, never()).deferNextRun();
    }

    @Test
    void aRejectedManualRunLeavesTheRealScheduleUntouched() {
        MediaGarrdProperties properties = new MediaGarrdProperties();
        BackupScheduleService realSchedule = new BackupScheduleService(properties, db);
        BackupOrchestratorService manual = new BackupOrchestratorService(runner, archives, realSchedule, snapshots, db, broadcaster);
        when(db.fetchServices()).thenReturn(List.of());
        Instant before = realSchedule.getNextRun();

        assertThrows(BackupOrchestratorService.RunRejectedException.class, manual::runBackup);

        assertEquals(before, realSchedule.getNextRun());
        manual.shutdownRunnerExecutor();
    }

    @Test
    void aScheduledRunWithoutServicesDefersTheNextCheck() {
        when(schedule.dueNow()).thenReturn(true);
        when(db.fetchServices()).thenReturn(List.of());

        orchestrator.scheduledRun();

        verify(schedule).deferNextRun();
        verify(broadcaster, never()).publishStarted(any());
    }

    @Test
    void aScheduledRunRejectedBecauseOneIsInProgressDoesNotDefer() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(db.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.run(eq(RADARR), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return result("radarr", COMPLETED);
        });
        when(schedule.dueNow()).thenReturn(true);

        CompletableFuture<BackupRunResult> manual = CompletableFuture.supplyAsync(orchestrator::runBackup);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(BackupOrchestratorService.RunRejectedException.class, () -> orchestrator.runBackup(true));

        verify(schedule, never()).deferNextRun();
        release.countDown();
        manual.get(5, TimeUnit.SECONDS);
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
        verify(db).addNewBackupRecord(result);
        verify(archives).removeRunDirectory(RUN_DIR);
    }

    @Test
    void partialWhenSomeServicesFail() {
        givenResults(COMPLETED, FAILED);

        BackupRunResult result = orchestrator.runBackup();

        assertEquals(PARTIAL, result.status());
        assertEquals("sonarr: boom", result.errorMessage());
    }

    @Test
    void partialWhenOnlyTheDatabaseSnapshotFails() {
        givenResults(COMPLETED, COMPLETED);
        when(snapshots.snapshot(RUN_DIR)).thenReturn(snapshotResult(FAILED));

        BackupRunResult result = orchestrator.runBackup();

        assertEquals(PARTIAL, result.status());
        assertEquals(SNAPSHOT + ": boom", result.errorMessage());
        assertEquals(ARCHIVE, result.archive());
        result.serviceResults().forEach(r -> assertEquals("archive-1", r.runId()));
        verify(db).addNewBackupRecord(result);
    }

    @Test
    void snapshotFailureIsAddedToTheServiceErrors() {
        givenResults(COMPLETED, FAILED);
        when(snapshots.snapshot(RUN_DIR)).thenReturn(snapshotResult(FAILED));

        BackupRunResult result = orchestrator.runBackup();

        assertEquals(PARTIAL, result.status());
        assertEquals("sonarr: boom\n" + SNAPSHOT + ": boom", result.errorMessage());
    }

    @Test
    void aThrowingSnapshotStillProducesAPartialArchive() {
        givenResults(COMPLETED, COMPLETED);
        when(snapshots.snapshot(RUN_DIR)).thenThrow(new IllegalStateException("docker down"));

        BackupRunResult result = orchestrator.runBackup();

        assertEquals(PARTIAL, result.status());
        assertEquals(SNAPSHOT + ": docker down", result.errorMessage());
        assertEquals(ARCHIVE, result.archive());
        verify(db).addNewBackupRecord(result);
    }

    @Test
    void snapshotRunsBeforeTheArchiveIsCreated() {
        givenResults(COMPLETED, COMPLETED);

        orchestrator.runBackup();

        var order = inOrder(snapshots, archives);
        order.verify(snapshots).snapshot(RUN_DIR);
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
        verify(snapshots, never()).snapshot(any());
        verify(archives).removeRunDirectory(RUN_DIR);
        assertFalse(orchestrator.isRunning());

        ArgumentCaptor<BackupRunResult> recorded = ArgumentCaptor.forClass(BackupRunResult.class);
        verify(db).addNewBackupRecord(recorded.capture());
        BackupRunResult run = recorded.getValue();
        assertEquals(FAILED, run.status());
        assertNull(run.archive());
        assertEquals("radarr: boom\nsonarr: boom", run.errorMessage());
        run.serviceResults().forEach(r -> assertEquals(run.runId(), r.runId()));
    }

    @Test
    void runnerExceptionsBecomeFailedResults() {
        when(db.fetchServices()).thenReturn(List.of(RADARR, SONARR));
        when(runner.run(RADARR, RUN_DIR)).thenThrow(new IllegalStateException("crashed"));
        when(runner.run(SONARR, RUN_DIR)).thenReturn(result("sonarr", COMPLETED));

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
        verify(db, never()).addNewBackupRecord(any());
        assertFalse(orchestrator.isRunning());
    }

    @Test
    void rejectsConcurrentRuns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(db.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.run(eq(RADARR), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return result("radarr", COMPLETED);
        });

        CompletableFuture<BackupRunResult> first = CompletableFuture.supplyAsync(orchestrator::runBackup);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertTrue(orchestrator.isRunning());
        assertThrows(BackupOrchestratorService.RunRejectedException.class, orchestrator::runBackup);

        release.countDown();
        assertEquals(COMPLETED, first.get(5, TimeUnit.SECONDS).status());
        assertFalse(orchestrator.isRunning());
    }

    @Test
    void configChangesRunWhileIdle() {
        assertEquals("changed", orchestrator.whileIdle(() -> "changed"));
    }

    @Test
    void configChangesAreRejectedDuringABackupRun() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(db.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.run(eq(RADARR), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return result("radarr", COMPLETED);
        });

        CompletableFuture<BackupRunResult> run = CompletableFuture.supplyAsync(orchestrator::runBackup);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertThrows(BackupOrchestratorService.BackupInProgressException.class, () -> orchestrator.whileIdle(() -> "changed"));

        release.countDown();
        run.get(5, TimeUnit.SECONDS);
        assertEquals("changed", orchestrator.whileIdle(() -> "changed"));
    }

    @Test
    void backupWaitsForInFlightConfigChange() throws Exception {
        CountDownLatch changing = new CountDownLatch(1);
        CountDownLatch finishChange = new CountDownLatch(1);
        givenResults(COMPLETED, COMPLETED);

        CompletableFuture<String> change = CompletableFuture.supplyAsync(() -> orchestrator.whileIdle(() -> {
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
        verify(db, never()).fetchServices();

        finishChange.countDown();
        assertEquals("changed", change.get(5, TimeUnit.SECONDS));
        assertEquals(COMPLETED, run.get(5, TimeUnit.SECONDS).status());
    }

    @Test
    void publishesStartedBeforeWorkAndFinishedWithTheRunStatus() {
        givenResults(COMPLETED, COMPLETED);

        orchestrator.runBackup();

        var order = inOrder(broadcaster, runner, db);
        order.verify(db).fetchServices();
        order.verify(broadcaster).publishStarted(any());
        order.verify(runner, org.mockito.Mockito.atLeastOnce()).run(any(), any());
        order.verify(broadcaster).publishFinished(eq(COMPLETED), any(), any());
        verify(broadcaster, org.mockito.Mockito.times(1)).publishStarted(any());
        verify(broadcaster, org.mockito.Mockito.times(1)).publishFinished(any(), any(), any());
    }

    @Test
    void publishesPartialWhenSomeServicesFail() {
        givenResults(COMPLETED, FAILED);

        orchestrator.runBackup();

        verify(broadcaster).publishFinished(eq(PARTIAL), any(), any());
    }

    @Test
    void publishesFailedWhenEveryServiceFails() {
        givenResults(FAILED, FAILED);

        assertThrows(IllegalStateException.class, orchestrator::runBackup);

        verify(broadcaster).publishFinished(eq(FAILED), any(), any());
    }

    @Test
    void publishesFailedWhenArchivingThrows() {
        givenResults(COMPLETED, COMPLETED);
        when(archives.createArchive(RUN_DIR)).thenThrow(new IllegalStateException("disk full"));

        assertThrows(IllegalStateException.class, orchestrator::runBackup);

        verify(broadcaster).publishFinished(eq(FAILED), any(), any());
    }

    @Test
    void publishesFailedWhenTheRunDirectoryCannotBeCreated() {
        when(db.fetchServices()).thenReturn(List.of(RADARR));
        when(archives.createRunDirectory()).thenThrow(new IllegalStateException("no space"));

        assertThrows(IllegalStateException.class, orchestrator::runBackup);

        verify(broadcaster).publishStarted(any());
        verify(broadcaster).publishFinished(eq(FAILED), any(), any());
        assertFalse(orchestrator.isRunning());
    }

    @Test
    void publishesFailedWhenRecordingTheRunThrows() {
        givenResults(COMPLETED, COMPLETED);
        doThrow(new IllegalStateException("db locked")).when(db).addNewBackupRecord(any());

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
        assertEquals("changed", orchestrator.whileIdle(() -> "changed"));
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
        when(db.fetchServices()).thenReturn(List.of(RADARR));
        when(runner.run(eq(RADARR), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return result("radarr", COMPLETED);
        });

        CompletableFuture<BackupRunResult> first = CompletableFuture.supplyAsync(orchestrator::runBackup);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(BackupOrchestratorService.RunRejectedException.class, orchestrator::runBackup);
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
        when(db.fetchServices()).thenReturn(List.of(RADARR, SONARR));
        when(runner.run(RADARR, RUN_DIR)).thenReturn(result("radarr", radarrStatus));
        when(runner.run(SONARR, RUN_DIR)).thenReturn(result("sonarr", sonarrStatus));
    }

    private static BackupServiceResult snapshotResult(String status) {
        return result(SNAPSHOT, status);
    }

    private static BackupServiceResult result(String name, String status) {
        return new BackupServiceResult(null, status, "start", "end", name, COMPLETED.equals(status) ? null : "boom");
    }
}
