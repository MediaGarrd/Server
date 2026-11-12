package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.PARTIAL;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.model.FetchedBackupRecord;
import wellatleastitried.mediagarrdServer.model.FetchedBackupServiceRecord;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;

class DatabaseServiceTest {

    private static final ServiceDefinition RADARR = new ServiceDefinition("radarr", List.of("/srv/radarr/config", "/srv/radarr/data"));

    @TempDir
    Path tempDir;

    private MediaGarrdProperties properties;
    private DatabaseService db;

    @BeforeEach
    void setUp() {
        properties = new MediaGarrdProperties();
        properties.setDatabasePath(tempDir.resolve("nested/mediagarrd.db").toString());
        db = new DatabaseService(properties);
    }

    @Test
    void createsDatabaseFileAndParentDirectories() {
        assertTrue(Files.isRegularFile(tempDir.resolve("nested/mediagarrd.db")));
        assertTrue(db.verifyDatabaseIntegrity());
        assertTrue(db.verifyDatabaseIntegrity());
    }

    @Test
    void storesBackupRecordWithEveryServiceResult() {
        db.addNewBackupRecord(backupRun("run-1", "2026-01-01 00:00:00",
            serviceResult("jellyfin", COMPLETED, null),
            serviceResult("sonarr", PARTIAL, "one path failed"),
            serviceResult("qbittorrent", FAILED, "no paths copied")));

        FetchedBackupRecord record = db.fetchBackupById("run-1").orElseThrow();

        assertEquals("run-1", record.archiveId());
        assertEquals("2026-01-01 00:00:00", record.startTime());
        assertEquals(COMPLETED, record.status());
        assertEquals(Path.of("run-1.zip").toString(), record.filePath());
        assertEquals(
            Set.of(
                new FetchedBackupServiceRecord("jellyfin", COMPLETED, "2026-01-01 00:00:00", "2026-01-01 00:00:01", null),
                new FetchedBackupServiceRecord("sonarr", PARTIAL, "2026-01-01 00:00:00", "2026-01-01 00:00:01", "one path failed"),
                new FetchedBackupServiceRecord("qbittorrent", FAILED, "2026-01-01 00:00:00", "2026-01-01 00:00:01", "no paths copied")),
            Set.copyOf(record.fetchedServices()));
    }

    @Test
    void storesFailedRunWithoutArchive() {
        db.addNewBackupRecord(new BackupRunResult("failed-1", "2026-01-01 00:00:00", "2026-01-01 00:00:01", FAILED,
            List.of(serviceResult("radarr", FAILED, "boom")), null, "radarr: boom"));

        FetchedBackupRecord record = db.fetchBackupById("failed-1").orElseThrow();

        assertEquals(FAILED, record.status());
        assertNull(record.filePath());
        assertEquals("radarr: boom", record.errorMessage());
        assertEquals(1, record.fetchedServices().size());
    }

    @Test
    void fetchesLatestBackupRecordByStartTime() {
        db.addNewBackupRecord(backupRun("older", "2026-01-01 00:00:00"));
        db.addNewBackupRecord(backupRun("newer", "2026-02-01 00:00:00"));

        assertEquals("newer", db.fetchLatestBackupRecord().orElseThrow().archiveId());
    }

    @Test
    void returnsEmptyForMissingBackupRecords() {
        assertTrue(db.fetchBackupById("missing").isEmpty());
        assertTrue(db.fetchLatestBackupRecord().isEmpty());
    }

    @Test
    void deletesBackupRecord() {
        db.addNewBackupRecord(backupRun("run-1", "2026-01-01 00:00:00"));

        db.deleteBackupRecordById("run-1");

        assertTrue(db.fetchBackupById("run-1").isEmpty());
    }

    @Test
    void deletingBackupRecordCascadesToServiceRows() throws Exception {
        db.addNewBackupRecord(backupRun("run-1", "2026-01-01 00:00:00", serviceResult("radarr", COMPLETED, null)));
        assertEquals(1, count("backup_services"));

        db.deleteBackupRecordById("run-1");

        assertEquals(0, count("backup_services"));
    }

    @Test
    void migrationRemovesOrphanedServiceRows() throws Exception {
        try (var connection = rawConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO backup_services (backup_id, service_name, status) VALUES (42, 'radarr', 'completed')");
            statement.execute("PRAGMA user_version = 1");
        }

        new DatabaseService(properties);

        assertEquals(0, count("backup_services"));
        try (var connection = rawConnection(); var rs = connection.createStatement().executeQuery("PRAGMA user_version")) {
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    void storesAndFetchesServicesWithPathOrderPreserved() {
        assertEquals(List.of(), db.fetchServices());

        db.addService(RADARR);

        assertEquals(Optional.of(RADARR), db.fetchService("radarr"));
        assertEquals(Optional.of(RADARR), db.fetchService("RADARR"));
        assertEquals(List.of(RADARR), db.fetchServices());
    }

    @Test
    void listsServicesSortedByNameIgnoringCase() {
        db.addService(new ServiceDefinition("sonarr", List.of("/a")));
        db.addService(new ServiceDefinition("Jellyfin", List.of("/b")));
        db.addService(new ServiceDefinition("radarr", List.of("/c")));

        assertEquals(List.of("Jellyfin", "radarr", "sonarr"), db.fetchServices().stream().map(ServiceDefinition::name).toList());
    }

    @Test
    void rejectsDuplicateServiceNamesIgnoringCase() {
        db.addService(RADARR);

        assertThrows(IllegalStateException.class, () -> db.addService(new ServiceDefinition("Radarr", List.of("/other"))));
    }

    @Test
    void updatesServiceNameAndPaths() {
        db.addService(RADARR);
        ServiceDefinition renamed = new ServiceDefinition("radarr-4k", List.of("/srv/radarr-4k"));

        assertTrue(db.updateService("radarr", renamed));

        assertTrue(db.fetchService("radarr").isEmpty());
        assertEquals(Optional.of(renamed), db.fetchService("radarr-4k"));
        assertFalse(db.updateService("missing", renamed));
    }

    @Test
    void removesService() {
        db.addService(RADARR);

        assertTrue(db.removeService("radarr"));
        assertFalse(db.removeService("radarr"));
        assertEquals(List.of(), db.fetchServices());
    }

    @Test
    void savesAndOverwritesSettings() {
        assertTrue(db.fetchSetting("backup_root").isEmpty());

        db.saveSetting("backup_root", "/first");
        db.saveSetting("backup_root", "/second");

        assertEquals(Optional.of("/second"), db.fetchSetting("backup_root"));
    }

    @Test
    void persistsDataAcrossInstances() {
        db.addService(RADARR);
        db.saveSetting("backup_root", "/backups");

        DatabaseService reopened = new DatabaseService(properties);

        assertEquals(List.of(RADARR), reopened.fetchServices());
        assertEquals(Optional.of("/backups"), reopened.fetchSetting("backup_root"));
    }

    @Test
    void failsLoudlyWhenDatabaseIsUnusable() {
        properties.setDatabasePath(tempDir.toString());
        DatabaseService broken = new DatabaseService(properties);

        assertFalse(broken.verifyDatabaseIntegrity());
        assertThrows(IllegalStateException.class, broken::fetchServices);
        assertThrows(IllegalStateException.class, () -> broken.addService(RADARR));
        assertThrows(IllegalStateException.class, () -> broken.fetchSetting("backup_root"));
    }

    @Test
    void verifiesOnlyOnceWhileTheSchemaIsUnchanged() {
        AtomicInteger verifications = new AtomicInteger();
        DatabaseService counted = new DatabaseService(properties) {
            @Override
            protected synchronized boolean verifyDatabaseIntegrity() {
                verifications.incrementAndGet();
                return super.verifyDatabaseIntegrity();
            }
        };

        counted.addService(RADARR);
        counted.fetchServices();
        counted.fetchSetting("backup_root");

        assertEquals(1, verifications.get());
    }

    @Test
    void rebuildsWhenTheDatabaseFileIsDeleted() throws Exception {
        db.addService(RADARR);

        Files.delete(databaseFile());

        assertEquals(List.of(), db.fetchServices());
        db.addService(RADARR);
        assertEquals(List.of(RADARR), db.fetchServices());
    }

    @Test
    void rebuildsWhenTheDatabaseDirectoryIsDeleted() throws Exception {
        Files.delete(databaseFile());
        Files.delete(databaseFile().getParent());

        db.saveSetting("backup_root", "/backups");

        assertEquals(Optional.of("/backups"), db.fetchSetting("backup_root"));
    }

    @Test
    void rebuildsWhenATableIsDropped() throws Exception {
        try (var connection = rawConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE services");
        }

        db.addService(RADARR);

        assertEquals(List.of(RADARR), db.fetchServices());
    }

    @Test
    void migratesWhenAnOlderDatabaseIsSwappedIn() throws Exception {
        try (var connection = rawConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO backup_services (backup_id, service_name, status) VALUES (42, 'radarr', 'completed')");
            statement.execute("PRAGMA user_version = 1");
        }

        db.fetchServices();

        assertEquals(0, count("backup_services"));
    }

    @Test
    void movesACorruptDatabaseAsideOnStartup() throws Exception {
        Files.writeString(databaseFile(), "x".repeat(4096));

        DatabaseService recovered = new DatabaseService(properties);

        recovered.addService(RADARR);
        assertEquals(List.of(RADARR), recovered.fetchServices());
        assertEquals(List.of("x".repeat(4096)), quarantinedContents());
    }

    @Test
    void movesACorruptDatabaseAsideWhileRunning() throws Exception {
        db.addService(RADARR);
        Files.writeString(databaseFile(), "x".repeat(4096));

        assertEquals(List.of(), db.fetchServices());
        assertEquals(1, quarantinedContents().size());
    }

    @Test
    void snapshotIsAValidCopyWithTheSameRowsAndVersion() throws Exception {
        db.addService(RADARR);
        db.saveSetting("backup_root", "/backups");
        db.addNewBackupRecord(backupRun("run-1", "2026-01-01 00:00:00", serviceResult("radarr", COMPLETED, null)));
        Path snapshot = tempDir.resolve("snapshot.db");

        db.snapshotTo(snapshot);

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + snapshot); var statement = connection.createStatement()) {
            assertEquals("ok", statement.executeQuery("PRAGMA quick_check").getString(1));
            assertEquals(2, statement.executeQuery("PRAGMA user_version").getInt(1));
            assertEquals("/backups", statement.executeQuery("SELECT value FROM settings WHERE key = 'backup_root'").getString(1));
            assertEquals("radarr", statement.executeQuery("SELECT name FROM services").getString(1));
            assertEquals("run-1", statement.executeQuery("SELECT archive_id FROM backups").getString(1));
            assertEquals("radarr", statement.executeQuery("SELECT service_name FROM backup_services").getString(1));
        }
        assertEquals(List.of(RADARR), db.fetchServices());
    }

    @Test
    void snapshotHandlesQuotesInTheTargetPath() throws Exception {
        Path snapshot = Files.createDirectories(tempDir.resolve("it's here")).resolve("snapshot.db");

        db.snapshotTo(snapshot);

        assertTrue(Files.isRegularFile(snapshot));
    }

    @Test
    void snapshotRefusesToOverwriteAnExistingFile() throws Exception {
        Path existing = tempDir.resolve("existing.db");
        Files.writeString(existing, "keep me");

        assertThrows(IOException.class, () -> db.snapshotTo(existing));

        assertEquals("keep me", Files.readString(existing));
    }

    @Test
    void snapshotFailsForAMissingDatabaseWithoutCreatingOne() throws Exception {
        Files.delete(databaseFile());
        Path snapshot = tempDir.resolve("snapshot.db");

        assertThrows(IOException.class, () -> db.snapshotTo(snapshot));

        assertFalse(Files.exists(databaseFile()));
        assertFalse(Files.exists(snapshot));
    }

    @Test
    void snapshotFailsForACorruptDatabaseWithoutMovingItAside() throws Exception {
        Files.writeString(databaseFile(), "x".repeat(4096));
        Path snapshot = tempDir.resolve("snapshot.db");

        assertThrows(SQLException.class, () -> db.snapshotTo(snapshot));

        assertFalse(Files.exists(snapshot));
        assertEquals("x".repeat(4096), Files.readString(databaseFile()));
        assertEquals(List.of(), quarantinedContents());
    }

    @Test
    void writesWaitWhileTheSnapshotIsBeingTaken() throws Exception {
        CountDownLatch snapshotting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DatabaseService paused = new DatabaseService(properties) {
            @Override
            protected Connection openSnapshotConnection(Path file) throws SQLException {
                snapshotting.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.openSnapshotConnection(file);
            }
        };
        paused.saveSetting("backup_root", "/before");

        CompletableFuture<Void> snapshot = CompletableFuture.runAsync(() -> {
            try {
                paused.snapshotTo(tempDir.resolve("snapshot.db"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(snapshotting.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> write = CompletableFuture.runAsync(() -> paused.saveSetting("backup_root", "/during"));
        CompletableFuture<Void> delete = CompletableFuture.runAsync(() -> paused.deleteBackupRecordById("none"));
        CompletableFuture<Void> insert = CompletableFuture.runAsync(() ->
            paused.addNewBackupRecord(backupRun("run-2", "2026-01-01 00:00:00", serviceResult("radarr", COMPLETED, null))));
        assertThrows(TimeoutException.class, () -> write.get(300, TimeUnit.MILLISECONDS));
        assertFalse(delete.isDone());
        assertFalse(insert.isDone());

        release.countDown();
        snapshot.get(5, TimeUnit.SECONDS);
        write.get(5, TimeUnit.SECONDS);
        delete.get(5, TimeUnit.SECONDS);
        insert.get(5, TimeUnit.SECONDS);

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("snapshot.db"));
            var statement = connection.createStatement()) {
            assertEquals("/before", statement.executeQuery("SELECT value FROM settings WHERE key = 'backup_root'").getString(1));
            assertFalse(statement.executeQuery("SELECT 1 FROM backups").next());
        }
        assertEquals(Optional.of("/during"), paused.fetchSetting("backup_root"));
        assertTrue(paused.fetchBackupById("run-2").isPresent());
    }

    @Test
    void snapshotsDoNotDeadlockWithIntegrityChecksAndWrites() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            List<CompletableFuture<Void>> work = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int worker = i;
                work.add(CompletableFuture.runAsync(() -> {
                    for (int n = 0; n < 15; n++) {
                        try {
                            db.snapshotTo(tempDir.resolve("snapshot-" + worker + "-" + n + ".db"));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }));
                work.add(CompletableFuture.runAsync(() -> {
                    for (int n = 0; n < 30; n++) {
                        db.saveSetting("backup_root", "/" + n);
                        db.fetchServices();
                        db.verifyDatabaseIntegrity();
                    }
                }));
            }
            CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).get();
        });
    }

    private Path databaseFile() {
        return Path.of(properties.getDatabasePath());
    }

    private List<String> quarantinedContents() throws IOException {
        try (var files = Files.list(databaseFile().getParent())) {
            return files
                .filter(file -> file.getFileName().toString().startsWith("mediagarrd.db.corrupt-"))
                .map(file -> {
                    try {
                        return Files.readString(file);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .toList();
        }
    }

    private Connection rawConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + properties.getDatabasePath());
    }

    private int count(String table) throws SQLException {
        try (var connection = rawConnection(); var rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.getInt(1);
        }
    }

    private static BackupRunResult backupRun(String id, String startTime, BackupServiceResult... services) {
        BackupArchive archive = new BackupArchive(id, id + ".zip", Path.of(id + ".zip"), 0, Instant.parse("2026-01-01T00:00:00Z"));
        return new BackupRunResult(id, startTime, startTime, COMPLETED, List.of(services), archive, null);
    }

    private static BackupServiceResult serviceResult(String name, String status, String error) {
        return new BackupServiceResult(null, status, "2026-01-01 00:00:00", "2026-01-01 00:00:01", name, error);
    }
}
