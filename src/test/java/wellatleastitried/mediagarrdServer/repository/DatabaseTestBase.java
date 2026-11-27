package wellatleastitried.mediagarrdServer.repository;

import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;

abstract class DatabaseTestBase {

    @TempDir
    Path tempDir;

    MediaGarrdProperties properties;
    TestDatabase db;

    @BeforeEach
    void setUp() {
        properties = new MediaGarrdProperties();
        properties.setDatabasePath(tempDir.resolve("nested/mediagarrd.db").toString());
        db = new TestDatabase(properties);
    }

    Path databaseFile() {
        return Path.of(properties.getDatabasePath());
    }

    List<String> quarantinedContents() throws IOException {
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

    Connection rawConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + properties.getDatabasePath());
    }

    int count(String table) throws SQLException {
        try (var connection = rawConnection(); var rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.getInt(1);
        }
    }

    static BackupRunResult backupRun(String id, String startTime, BackupServiceResult... services) {
        BackupArchive archive = new BackupArchive(id, id + ".zip", Path.of(id + ".zip"), 0, Instant.parse("2026-01-01T00:00:00Z"));
        return new BackupRunResult(id, startTime, startTime, COMPLETED, List.of(services), archive, null);
    }

    static BackupServiceResult serviceResult(String name, String status, String error) {
        return new BackupServiceResult(null, status, "2026-01-01 00:00:00", "2026-01-01 00:00:01", name, error);
    }
}
