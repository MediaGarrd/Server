package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;

class BackupOrchestratorServiceTest {

    private static final ServiceDefinition RADARR = new ServiceDefinition("radarr", List.of("/srv/radarr"));
    private static final ServiceDefinition SONARR = new ServiceDefinition("sonarr", List.of("/srv/sonarr"));
    private static final Path RUN_DIR = Path.of("/tmp/run");
    private static final BackupArchive ARCHIVE = new BackupArchive("archive-1", "archive-1.zip", Path.of("archive-1.zip"), 1, Instant.EPOCH);

    private final ServiceBackupRunner runner = mock(ServiceBackupRunner.class);
    private final BackupArchiveService archives = mock(BackupArchiveService.class);
    private final BackupScheduleService schedule = mock(BackupScheduleService.class);
    private final DatabaseService db = mock(DatabaseService.class);
    private final BackupOrchestratorService orchestrator = new BackupOrchestratorService(runner, archives, schedule, db);

    @BeforeEach
    void setUp() {
        when(archives.createRunDirectory()).thenReturn(RUN_DIR);
        when(archives.createArchive(RUN_DIR)).thenReturn(ARCHIVE);
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
    }

    @Test
    void completedWhenEveryServiceSucceeds() {
        givenResults(COMPLETED, COMPLETED);

        BackupRunResult result = orchestrator.runBackup();

        assertEquals(COMPLETED, result.status());
        assertEquals("", result.errorMessage());
        assertEquals(List.of("radarr", "sonarr"), result.serviceNames());
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

    private void givenResults(String radarrStatus, String sonarrStatus) {
        when(db.fetchServices()).thenReturn(List.of(RADARR, SONARR));
        when(runner.run(RADARR, RUN_DIR)).thenReturn(result("radarr", radarrStatus));
        when(runner.run(SONARR, RUN_DIR)).thenReturn(result("sonarr", sonarrStatus));
    }

    private static BackupServiceResult result(String name, String status) {
        return new BackupServiceResult(null, status, "start", "end", name, COMPLETED.equals(status) ? null : "boom");
    }
}
