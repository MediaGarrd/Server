package wellatleastitried.mediagarrdServer.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import wellatleastitried.mediagarrdServer.model.ServiceDefinition;

class SqliteDatabaseTest extends DatabaseTestBase {

    private static final ServiceDefinition RADARR = new ServiceDefinition("radarr", List.of("/srv/radarr/config", "/srv/radarr/data"));

    @Test
    void createsDatabaseFileAndParentDirectories() {
        assertTrue(Files.isRegularFile(tempDir.resolve("nested/mediagarrd.db")));
        assertTrue(db.database.verifyDatabaseIntegrity());
    }

    @Test
    void migrationRemovesOrphanedServiceRows() throws Exception {
        try (var connection = rawConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO backup_services (backup_id, service_name, status) VALUES (42, 'radarr', 'completed')");
            statement.execute("PRAGMA user_version = 1");
        }

        new TestDatabase(properties);

        assertEquals(0, count("backup_services"));
        try (var connection = rawConnection(); var rs = connection.createStatement().executeQuery("PRAGMA user_version")) {
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    void persistsDataAcrossInstances() {
        db.services.addService(RADARR);
        db.settings.saveSetting("backup_root", "/backups");

        TestDatabase reopened = new TestDatabase(properties);

        assertEquals(List.of(RADARR), reopened.services.fetchServices());
        assertEquals(Optional.of("/backups"), reopened.settings.fetchSetting("backup_root"));
    }

    @Test
    void failsLoudlyWhenDatabaseIsUnusable() {
        properties.setDatabasePath(tempDir.toString());
        TestDatabase broken = new TestDatabase(properties);

        assertFalse(broken.database.verifyDatabaseIntegrity());
        assertThrows(IllegalStateException.class, broken.services::fetchServices);
        assertThrows(IllegalStateException.class, () -> broken.services.addService(RADARR));
        assertThrows(IllegalStateException.class, () -> broken.settings.fetchSetting("backup_root"));
    }

    @Test
    void verifiesOnlyOnceWhileTheSchemaIsUnchanged() {
        AtomicInteger verifications = new AtomicInteger();
        TestDatabase counted = new TestDatabase(new SqliteDatabase(properties) {
            @Override
            protected synchronized boolean verifyDatabaseIntegrity() {
                verifications.incrementAndGet();
                return super.verifyDatabaseIntegrity();
            }
        });

        counted.services.addService(RADARR);
        counted.services.fetchServices();
        counted.settings.fetchSetting("backup_root");

        assertEquals(1, verifications.get());
    }

    @Test
    void rebuildsWhenTheDatabaseFileIsDeleted() throws Exception {
        db.services.addService(RADARR);

        Files.delete(databaseFile());

        assertEquals(List.of(), db.services.fetchServices());
        db.services.addService(RADARR);
        assertEquals(List.of(RADARR), db.services.fetchServices());
    }

    @Test
    void rebuildsWhenTheDatabaseDirectoryIsDeleted() throws Exception {
        Files.delete(databaseFile());
        Files.delete(databaseFile().getParent());

        db.settings.saveSetting("backup_root", "/backups");

        assertEquals(Optional.of("/backups"), db.settings.fetchSetting("backup_root"));
    }

    @Test
    void rebuildsWhenATableIsDropped() throws Exception {
        try (var connection = rawConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE services");
        }

        db.services.addService(RADARR);

        assertEquals(List.of(RADARR), db.services.fetchServices());
    }

    @Test
    void migratesWhenAnOlderDatabaseIsSwappedIn() throws Exception {
        try (var connection = rawConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO backup_services (backup_id, service_name, status) VALUES (42, 'radarr', 'completed')");
            statement.execute("PRAGMA user_version = 1");
        }

        db.services.fetchServices();

        assertEquals(0, count("backup_services"));
    }

    @Test
    void movesACorruptDatabaseAsideOnStartup() throws Exception {
        Files.writeString(databaseFile(), "x".repeat(4096));

        TestDatabase recovered = new TestDatabase(properties);

        recovered.services.addService(RADARR);
        assertEquals(List.of(RADARR), recovered.services.fetchServices());
        assertEquals(List.of("x".repeat(4096)), quarantinedContents());
    }

    @Test
    void movesACorruptDatabaseAsideWhileRunning() throws Exception {
        db.services.addService(RADARR);
        Files.writeString(databaseFile(), "x".repeat(4096));

        assertEquals(List.of(), db.services.fetchServices());
        assertEquals(1, quarantinedContents().size());
    }

    @Test
    void notifiesListenersOnceAfterARuntimeRebuildFromAnotherThread() throws Exception {
        List<String> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch notified = new CountDownLatch(1);
        db.database.addRebuildListener(() -> {
            threads.add(Thread.currentThread().getName());
            notified.countDown();
        });
        Files.writeString(databaseFile(), "x".repeat(4096));

        db.services.fetchServices();
        db.services.fetchServices();

        assertTrue(notified.await(10, TimeUnit.SECONDS));
        assertEquals(1, threads.size());
        assertFalse(threads.contains(Thread.currentThread().getName()));
    }

    @Test
    void doesNotNotifyListenersForAMigrationOnly() throws Exception {
        CountDownLatch notified = new CountDownLatch(1);
        db.database.addRebuildListener(notified::countDown);
        try (var connection = rawConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE services");
        }

        db.services.fetchServices();

        assertFalse(notified.await(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void snapshotIsAValidCopyWithTheSameRowsAndVersion() throws Exception {
        db.services.addService(RADARR);
        db.settings.saveSetting("backup_root", "/backups");
        db.backups.addNewBackupRecord(backupRun("run-1", "2026-01-01 00:00:00", serviceResult("radarr", COMPLETED, null)));
        Path snapshot = tempDir.resolve("snapshot.db");

        db.database.snapshotTo(snapshot);

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + snapshot); var statement = connection.createStatement()) {
            assertEquals("ok", statement.executeQuery("PRAGMA quick_check").getString(1));
            assertEquals(2, statement.executeQuery("PRAGMA user_version").getInt(1));
            assertEquals("/backups", statement.executeQuery("SELECT value FROM settings WHERE key = 'backup_root'").getString(1));
            assertEquals("radarr", statement.executeQuery("SELECT name FROM services").getString(1));
            assertEquals("run-1", statement.executeQuery("SELECT archive_id FROM backups").getString(1));
            assertEquals("radarr", statement.executeQuery("SELECT service_name FROM backup_services").getString(1));
        }
        assertEquals(List.of(RADARR), db.services.fetchServices());
    }

    @Test
    void snapshotHandlesQuotesInTheTargetPath() throws Exception {
        Path snapshot = Files.createDirectories(tempDir.resolve("it's here")).resolve("snapshot.db");

        db.database.snapshotTo(snapshot);

        assertTrue(Files.isRegularFile(snapshot));
    }

    @Test
    void snapshotRefusesToOverwriteAnExistingFile() throws Exception {
        Path existing = tempDir.resolve("existing.db");
        Files.writeString(existing, "keep me");

        assertThrows(IOException.class, () -> db.database.snapshotTo(existing));

        assertEquals("keep me", Files.readString(existing));
    }

    @Test
    void snapshotFailsForAMissingDatabaseWithoutCreatingOne() throws Exception {
        Files.delete(databaseFile());
        Path snapshot = tempDir.resolve("snapshot.db");

        assertThrows(IOException.class, () -> db.database.snapshotTo(snapshot));

        assertFalse(Files.exists(databaseFile()));
        assertFalse(Files.exists(snapshot));
    }

    @Test
    void snapshotFailsForACorruptDatabaseWithoutMovingItAside() throws Exception {
        Files.writeString(databaseFile(), "x".repeat(4096));
        Path snapshot = tempDir.resolve("snapshot.db");

        assertThrows(SQLException.class, () -> db.database.snapshotTo(snapshot));

        assertFalse(Files.exists(snapshot));
        assertEquals("x".repeat(4096), Files.readString(databaseFile()));
        assertEquals(List.of(), quarantinedContents());
    }

    @Test
    void writesWaitWhileTheSnapshotIsBeingTaken() throws Exception {
        CountDownLatch snapshotting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestDatabase paused = new TestDatabase(new SqliteDatabase(properties) {
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
        });
        paused.settings.saveSetting("backup_root", "/before");

        CompletableFuture<Void> snapshot = CompletableFuture.runAsync(() -> {
            try {
                paused.database.snapshotTo(tempDir.resolve("snapshot.db"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(snapshotting.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> write = CompletableFuture.runAsync(() -> paused.settings.saveSetting("backup_root", "/during"));
        CompletableFuture<Void> delete = CompletableFuture.runAsync(() -> paused.backups.deleteBackupRecordById("none"));
        CompletableFuture<Void> insert = CompletableFuture.runAsync(() ->
            paused.backups.addNewBackupRecord(backupRun("run-2", "2026-01-01 00:00:00", serviceResult("radarr", COMPLETED, null))));
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
        assertEquals(Optional.of("/during"), paused.settings.fetchSetting("backup_root"));
        assertTrue(paused.backups.fetchBackupById("run-2").isPresent());
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
                            db.database.snapshotTo(tempDir.resolve("snapshot-" + worker + "-" + n + ".db"));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }));
                work.add(CompletableFuture.runAsync(() -> {
                    for (int n = 0; n < 30; n++) {
                        db.settings.saveSetting("backup_root", "/" + n);
                        db.services.fetchServices();
                        db.database.verifyDatabaseIntegrity();
                    }
                }));
            }
            CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).get();
        });
    }

    @Test
    void theDatabaseFileIsOwnerOnlyAndItsNewDirectoryIsPrivate() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(databaseFile())));
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(databaseFile().getParent())));
    }

    @Test
    void anExistingDatabaseFileIsTightenedButItsDirectoryIsLeftAlone() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path directory = Files.createDirectories(tempDir.resolve("existing"));
        Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        MediaGarrdPropertiesHolder.open(directory.resolve("old.db"));
        Files.setPosixFilePermissions(directory.resolve("old.db"), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));

        MediaGarrdPropertiesHolder.open(directory.resolve("old.db"));

        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("old.db"))));
        assertEquals("rwxr-xr-x", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
    }

    @Test
    void aFailedTransactionIsRolledBackAndRethrown() throws Exception {
        try (var connection = rawConnection()) {
            connection.setAutoCommit(false);
            assertThrows(SQLException.class, () -> db.database.inTransaction(connection, c -> {
                c.createStatement().executeUpdate("INSERT INTO settings (key, value) VALUES ('k', 'v')");
                c.createStatement().executeUpdate("INSERT INTO no_such_table VALUES (1)");
                return null;
            }));
        }
        assertEquals(0, count("settings"));
    }

    private static final class MediaGarrdPropertiesHolder {
        static void open(Path file) {
            var properties = new wellatleastitried.mediagarrdServer.MediaGarrdProperties();
            properties.setDatabasePath(file.toString());
            new TestDatabase(properties);
        }
    }
}
