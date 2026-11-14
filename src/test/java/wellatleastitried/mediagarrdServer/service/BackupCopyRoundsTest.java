package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.dir;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.file;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.link;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.tar;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.PARTIAL;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;
import wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.Item;

@Timeout(60)
class BackupCopyRoundsTest {

    private static final BackupArchive ARCHIVE = new BackupArchive("archive-1", "archive-1.zip", Path.of("archive-1.zip"), 1, Instant.EPOCH);
    private static final Path DATABASE = Path.of("/srv/app/db/mediagarrd.db");

    @TempDir
    Path run;

    private final DockerService docker = mock(DockerService.class);
    private final BackupArchiveService archives = mock(BackupArchiveService.class);
    private final DatabaseSnapshotService snapshots = mock(DatabaseSnapshotService.class);
    private final DatabaseService db = mock(DatabaseService.class);
    private final Map<String, Supplier<InputStream>> sources = new HashMap<>();
    private final BackupOrchestratorService orchestrator = new BackupOrchestratorService(
        new ServiceBackupRunner(docker, new ZipCompression(1)), archives, mock(BackupScheduleService.class), snapshots, db, mock(RunStatusBroadcaster.class));

    @BeforeEach
    void setUp() throws Exception {
        when(archives.createRunDirectory()).thenReturn(run);
        when(archives.createArchive(run)).thenReturn(ARCHIVE);
        when(docker.containerFor(any())).thenReturn("cid");
        when(docker.copyFrom(eq("cid"), anyString())).thenAnswer(invocation -> {
            Supplier<InputStream> source = sources.get(invocation.<String>getArgument(1));
            if (source == null) {
                throw new IllegalStateException("no such path");
            }
            return source.get();
        });
        snapshotSucceeds();
    }

    @AfterEach
    void tearDown() {
        orchestrator.shutdownRunnerExecutor();
    }

    private void snapshotSucceeds() {
        when(snapshots.snapshotWithHostPath(run)).thenReturn(new DatabaseSnapshotService.Outcome(result("_mediagarrd-server", COMPLETED), DATABASE));
    }

    private static BackupServiceResult result(String name, String status) {
        return new BackupServiceResult(null, status, "start", "end", name, null);
    }

    private void serve(String path, Item... items) {
        sources.put(path, () -> {
            try {
                return tar(items);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private BackupRunResult runBackup(ServiceDefinition... services) {
        when(db.fetchServices()).thenReturn(List.of(services));
        return orchestrator.runBackup();
    }

    private Map<String, String> part(String service, int index) throws IOException {
        return ArchiveTestSupport.partContents(run, run.resolve(service).resolve(index + ServiceBackupRunner.PART_SUFFIX));
    }

    private static BackupServiceResult resultOf(BackupRunResult run, String service) {
        return run.serviceResults().stream().filter(r -> r.serviceName().equals(service)).findFirst().orElseThrow();
    }

    @Test
    void aNestedPathIsSkippedWhenItsOwnerCopiedItAndCountsAsCompleted() throws Exception {
        ServiceDefinition media = new ServiceDefinition("media", List.of("/srv/media", "/srv/media/config"));
        serve("/srv/media", dir("media/"), file("media/a", "1"), dir("media/config/"), file("media/config/c", "2"));

        BackupRunResult result = runBackup(media);

        assertEquals(COMPLETED, result.status());
        assertEquals(COMPLETED, resultOf(result, "media").status());
        verify(docker, times(1)).copyFrom(eq("cid"), eq("/srv/media"));
        verify(docker, never()).copyFrom(eq("cid"), eq("/srv/media/config"));
        assertEquals(Map.of("srv/media/", "", "srv/media/a", "1", "srv/media/config/", "", "srv/media/config/c", "2"), part("media", 0));
        assertFalse(Files.exists(run.resolve("media/1.part")));
    }

    @Test
    void aNestedPathInAnotherServiceIsSkippedAndReportedByItsOwnOutcome() throws Exception {
        ServiceDefinition media = new ServiceDefinition("media", List.of("/srv/media"));
        ServiceDefinition config = new ServiceDefinition("config", List.of("/srv/media/config"));
        serve("/srv/media", dir("media/"), dir("media/config/"), file("media/config/c", "2"));

        BackupRunResult result = runBackup(media, config);

        assertEquals(COMPLETED, result.status());
        assertEquals(COMPLETED, resultOf(result, "config").status());
        assertNull(resultOf(result, "config").errorMessage());
        verify(docker, never()).copyFrom(eq("cid"), eq("/srv/media/config"));
    }

    @Test
    void whenTheOwnerFailsTheCoveredPathIsCopiedOnItsOwn() throws Exception {
        ServiceDefinition media = new ServiceDefinition("media", List.of("/srv/media"));
        ServiceDefinition config = new ServiceDefinition("config", List.of("/srv/media/config"));
        sources.put("/srv/media", () -> {
            throw new IllegalStateException("owner broke");
        });
        serve("/srv/media/config", dir("config/"), file("config/c", "2"));

        BackupRunResult result = runBackup(media, config);

        assertEquals(PARTIAL, result.status());
        assertEquals(FAILED, resultOf(result, "media").status());
        assertEquals(COMPLETED, resultOf(result, "config").status());
        assertEquals(Map.of("srv/media/config/", "", "srv/media/config/c", "2"), part("config", 0));
    }

    @Test
    void aCoveredPathThatFailsOnItsOwnAfterAFailedOwnerFailsOnlyItsService() throws Exception {
        ServiceDefinition media = new ServiceDefinition("media", List.of("/srv/media"));
        ServiceDefinition config = new ServiceDefinition("config", List.of("/srv/media/config"));
        ServiceDefinition other = new ServiceDefinition("other", List.of("/srv/other"));
        sources.put("/srv/media", () -> {
            throw new IllegalStateException("owner broke");
        });
        serve("/srv/other", dir("other/"));

        BackupRunResult result = runBackup(media, config, other);

        assertEquals(PARTIAL, result.status());
        assertEquals(FAILED, resultOf(result, "config").status());
        assertEquals("/srv/media/config: no such path", resultOf(result, "config").errorMessage());
    }

    @Test
    void aSymbolicLinkAtTheCoveredPathIsNotStoredSoTheCoveredPathIsCopiedItself() throws Exception {
        ServiceDefinition media = new ServiceDefinition("media", List.of("/srv/a"));
        ServiceDefinition linked = new ServiceDefinition("linked", List.of("/srv/a/link"));
        serve("/srv/a", dir("a/"), link("a/link", TarConstants.LF_SYMLINK, "/mnt/x"));
        serve("/srv/a/link", dir("link/"), file("link/data", "real"));

        BackupRunResult result = runBackup(media, linked);

        assertEquals(COMPLETED, result.status());
        verify(docker).copyFrom("cid", "/srv/a/link");
        assertEquals(Map.of("srv/a/link/", "", "srv/a/link/data", "real"), part("linked", 0));
        assertFalse(part("media", 0).containsKey("srv/a/link"));
    }

    @Test
    void aChainOfNestedPathsFallsBackOneRoundAtATimeWithoutDuplicates() throws Exception {
        ServiceDefinition outer = new ServiceDefinition("outer", List.of("/srv/a"));
        ServiceDefinition middle = new ServiceDefinition("middle", List.of("/srv/a/b"));
        ServiceDefinition inner = new ServiceDefinition("inner", List.of("/srv/a/b/c"));
        sources.put("/srv/a", () -> {
            throw new IllegalStateException("outer broke");
        });
        serve("/srv/a/b", dir("b/"), dir("b/c/"), file("b/c/x", "1"));

        BackupRunResult result = runBackup(outer, middle, inner);

        assertEquals(FAILED, resultOf(result, "outer").status());
        assertEquals(COMPLETED, resultOf(result, "middle").status());
        assertEquals(COMPLETED, resultOf(result, "inner").status());
        verify(docker, never()).copyFrom(eq("cid"), eq("/srv/a/b/c"));
        assertEquals(3, part("middle", 0).size());
    }

    @Test
    void ofSeveralServicesWithTheSamePathOnlyTheFirstByNameCopiesIt() throws Exception {
        ServiceDefinition b = new ServiceDefinition("b", List.of("/srv/shared"));
        ServiceDefinition a = new ServiceDefinition("a", List.of("/srv/shared"));
        ServiceDefinition c = new ServiceDefinition("c", List.of("/srv/shared"));
        serve("/srv/shared", dir("shared/"), file("shared/x", "1"));

        BackupRunResult result = runBackup(b, a, c);

        assertEquals(COMPLETED, result.status());
        verify(docker, times(1)).copyFrom("cid", "/srv/shared");
        assertTrue(Files.exists(run.resolve("a/0.part")));
        assertFalse(Files.exists(run.resolve("b/0.part")));
        assertFalse(Files.exists(run.resolve("c/0.part")));
    }

    @Test
    void whenTheOwnerOfSeveralEqualPathsFailsExactlyOneOtherCopiesIt() throws Exception {
        ServiceDefinition a = new ServiceDefinition("a", List.of("/srv/shared"));
        ServiceDefinition b = new ServiceDefinition("b", List.of("/srv/shared"));
        ServiceDefinition c = new ServiceDefinition("c", List.of("/srv/shared"));
        int[] calls = {0};
        sources.put("/srv/shared", () -> {
            if (calls[0]++ == 0) {
                throw new IllegalStateException("first broke");
            }
            try {
                return tar(dir("shared/"), file("shared/x", "1"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        BackupRunResult result = runBackup(a, b, c);

        assertEquals(2, calls[0]);
        assertEquals(FAILED, resultOf(result, "a").status());
        assertEquals(COMPLETED, resultOf(result, "b").status());
        assertEquals(COMPLETED, resultOf(result, "c").status());
        assertTrue(Files.exists(run.resolve("b/0.part")));
        assertFalse(Files.exists(run.resolve("c/0.part")));
    }

    @Test
    void aServiceKeepsItsOwnPathsWhileOneOfThemIsCoveredElsewhere() throws Exception {
        ServiceDefinition media = new ServiceDefinition("media", List.of("/srv/media"));
        ServiceDefinition mixed = new ServiceDefinition("mixed", List.of("/srv/media/config", "/etc/mixed"));
        serve("/srv/media", dir("media/"), dir("media/config/"));
        serve("/etc/mixed", dir("mixed/"), file("mixed/m", "m"));

        BackupRunResult result = runBackup(media, mixed);

        assertEquals(COMPLETED, resultOf(result, "mixed").status());
        assertFalse(Files.exists(run.resolve("mixed/0.part")));
        assertEquals(Map.of("etc/mixed/", "", "etc/mixed/m", "m"), part("mixed", 1));
    }

    @Test
    void theDatabaseAndItsSidecarsAreLeftOutOfServiceCopiesWhenTheSnapshotSucceeded() throws Exception {
        ServiceDefinition app = new ServiceDefinition("app", List.of("/srv/app"));
        serve("/srv/app", dir("app/"), dir("app/db/"), file("app/db/mediagarrd.db", "live"), file("app/db/mediagarrd.db-wal", "wal"),
            file("app/db/mediagarrd.db-shm", "shm"), file("app/db/mediagarrd.db-journal", "j"), link("app/db/hard", TarConstants.LF_LINK, "app/db/mediagarrd.db"),
            file("app/db/notes", "keep"));

        runBackup(app);

        assertEquals(Map.of("srv/app/", "", "srv/app/db/", "", "srv/app/db/notes", "keep"), part("app", 0));
    }

    @Test
    void theLiveDatabaseIsKeptWhenTheSnapshotFails() throws Exception {
        when(snapshots.snapshotWithHostPath(run)).thenReturn(new DatabaseSnapshotService.Outcome(result("_mediagarrd-server", FAILED), DATABASE));
        ServiceDefinition app = new ServiceDefinition("app", List.of("/srv/app"));
        serve("/srv/app", dir("app/"), dir("app/db/"), file("app/db/mediagarrd.db", "live"), file("app/db/mediagarrd.db-wal", "wal"));

        BackupRunResult result = runBackup(app);

        assertEquals(PARTIAL, result.status());
        assertEquals(Map.of("srv/app/", "", "srv/app/db/", "", "srv/app/db/mediagarrd.db", "live", "srv/app/db/mediagarrd.db-wal", "wal"), part("app", 0));
    }

    @Test
    void nothingIsExcludedWhenTheSnapshotThrows() throws Exception {
        when(snapshots.snapshotWithHostPath(run)).thenThrow(new IllegalStateException("docker down"));
        ServiceDefinition app = new ServiceDefinition("app", List.of("/srv/app"));
        serve("/srv/app", dir("app/"), file("app/mediagarrd.db", "live"));

        BackupRunResult result = runBackup(app);

        assertEquals(PARTIAL, result.status());
        assertEquals(Map.of("srv/app/", "", "srv/app/mediagarrd.db", "live"), part("app", 0));
        verify(archives, atLeastOnce()).createArchive(run);
    }

    @Test
    void theSnapshotIsTakenOnceBeforeAnyCopyStarts() throws Exception {
        ServiceDefinition app = new ServiceDefinition("app", List.of("/srv/app"));
        serve("/srv/app", dir("app/"));

        runBackup(app);

        var order = org.mockito.Mockito.inOrder(snapshots, docker);
        order.verify(snapshots).snapshotWithHostPath(run);
        order.verify(docker).copyFrom("cid", "/srv/app");
        verify(snapshots, times(1)).snapshotWithHostPath(run);
    }
}
