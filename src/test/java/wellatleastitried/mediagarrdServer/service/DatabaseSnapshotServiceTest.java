package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.List;

import org.apache.commons.compress.archivers.zip.X7875_NewUnix;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;

class DatabaseSnapshotServiceTest {

    private static final Path HOST_DATABASE = Path.of("/srv/mediagarrd/db/mediagarrd.db");

    @TempDir
    Path tempDir;

    private DatabaseService db;
    private DatabaseHostPathResolver hostPaths;
    private DatabaseSnapshotService snapshots;
    private Path run;

    @BeforeEach
    void setUp() throws Exception {
        MediaGarrdProperties properties = new MediaGarrdProperties();
        properties.setDatabasePath(tempDir.resolve("db/mediagarrd.db").toString());
        db = new DatabaseService(properties);
        hostPaths = mock(DatabaseHostPathResolver.class);
        when(hostPaths.resolve(db.databaseFile())).thenReturn(HOST_DATABASE);
        snapshots = new DatabaseSnapshotService(db, hostPaths);
        run = Files.createDirectories(tempDir.resolve("run"));
    }

    @Test
    void writesTheSnapshotAtTheHostPathWithTheLiveFileMetadata() throws Exception {
        db.addService(new ServiceDefinition("radarr", List.of("/srv/radarr")));
        Files.setPosixFilePermissions(db.databaseFile(), PosixFilePermissions.fromString("rw-r-----"));
        Integer uid = (Integer) Files.getAttribute(db.databaseFile(), "unix:uid");
        Integer gid = (Integer) Files.getAttribute(db.databaseFile(), "unix:gid");

        BackupServiceResult result = snapshots.snapshot(run);

        assertEquals(COMPLETED, result.status());
        assertEquals(DatabaseSnapshotService.SERVICE_NAME, result.serviceName());
        Path part = run.resolve(DatabaseSnapshotService.PART_DIRECTORY).resolve(DatabaseSnapshotService.PART_NAME);
        try (ZipFile zip = ZipFile.builder().setPath(part).get()) {
            assertEquals(List.of("srv/mediagarrd/db/mediagarrd.db"), Collections.list(zip.getEntries()).stream().map(ZipArchiveEntry::getName).toList());
            ZipArchiveEntry entry = zip.getEntry("srv/mediagarrd/db/mediagarrd.db");
            assertEquals(0640, entry.getUnixMode() & 07777);
            X7875_NewUnix owner = (X7875_NewUnix) entry.getExtraField(new X7875_NewUnix().getHeaderId());
            assertEquals(uid.longValue(), owner.getUID());
            assertEquals(gid.longValue(), owner.getGID());

            Path extracted = tempDir.resolve("extracted.db");
            Files.copy(zip.getInputStream(entry), extracted);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + extracted); var statement = connection.createStatement()) {
                assertEquals("ok", statement.executeQuery("PRAGMA quick_check").getString(1));
                assertEquals(2, statement.executeQuery("PRAGMA user_version").getInt(1));
                assertEquals("radarr", statement.executeQuery("SELECT name FROM services").getString(1));
            }
        }
        assertFalse(Files.exists(run.resolve(DatabaseSnapshotService.PART_DIRECTORY).resolve("snapshot.db")));
    }

    @Test
    void failsWithoutAPartWhenTheHostPathCannotBeResolved() throws Exception {
        when(hostPaths.resolve(db.databaseFile())).thenThrow(new IOException("no mount covers the database"));

        BackupServiceResult result = snapshots.snapshot(run);

        assertEquals(FAILED, result.status());
        assertEquals("no mount covers the database", result.errorMessage());
        assertNoPart();
    }

    @Test
    void failsWithoutAPartWhenTheDatabaseIsMissing() throws Exception {
        Files.delete(db.databaseFile());

        BackupServiceResult result = snapshots.snapshot(run);

        assertEquals(FAILED, result.status());
        assertEquals("Database file " + db.databaseFile() + " does not exist", result.errorMessage());
        assertFalse(Files.exists(db.databaseFile()));
        assertNoPart();
    }

    @Test
    void failsWithoutAPartWhenTheDatabaseIsCorrupt() throws Exception {
        Files.writeString(db.databaseFile(), "x".repeat(4096));

        BackupServiceResult result = snapshots.snapshot(run);

        assertEquals(FAILED, result.status());
        assertEquals("x".repeat(4096), Files.readString(db.databaseFile()));
        assertNoPart();
    }

    @Test
    void resolverFailuresOfAnyKindBecomeAFailedResult() throws Exception {
        when(hostPaths.resolve(db.databaseFile())).thenThrow(new IllegalStateException("docker down"));

        assertEquals("docker down", snapshots.snapshot(run).errorMessage());
    }

    private void assertNoPart() throws IOException {
        Path directory = run.resolve(DatabaseSnapshotService.PART_DIRECTORY);
        if (Files.exists(directory)) {
            try (var files = Files.list(directory)) {
                assertTrue(files.toList().isEmpty());
            }
        }
    }
}
