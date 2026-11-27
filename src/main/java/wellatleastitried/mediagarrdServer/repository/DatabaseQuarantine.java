package wellatleastitried.mediagarrdServer.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

final class DatabaseQuarantine {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseQuarantine.class);

    private static final DateTimeFormatter QUARANTINE_FORMAT = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneOffset.UTC);

    private static final List<String> DATABASE_FILE_SUFFIXES = List.of("", "-journal", "-wal", "-shm");

    private DatabaseQuarantine() {}

    static void checkNotCorrupt(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery("PRAGMA quick_check")) {
            String result = rs.next() ? rs.getString(1) : "no result";
            if (!"ok".equals(result)) {
                throw new CorruptDatabaseException("Database failed its integrity check: " + result);
            }
        }
    }

    static boolean isCorrupt(SQLException e) {
        if (e instanceof CorruptDatabaseException) {
            return true;
        }
        if (e instanceof SQLiteException sqlite) {
            int code = sqlite.getResultCode().code & 0xFF;
            return code == SQLiteErrorCode.SQLITE_CORRUPT.code || code == SQLiteErrorCode.SQLITE_NOTADB.code;
        }
        return false;
    }

    static boolean quarantine(Path databaseFile) {
        if (!Files.isRegularFile(databaseFile)) {
            return false;
        }
        String suffix = ".corrupt-" + QUARANTINE_FORMAT.format(Instant.now());
        try {
            for (String fileSuffix : DATABASE_FILE_SUFFIXES) {
                Path file = Path.of(databaseFile + fileSuffix);
                if (Files.isRegularFile(file)) {
                    Files.move(file, Path.of(file + suffix));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Database {} is corrupt and could not be moved aside", databaseFile, e);
            return false;
        }
        LOGGER.error("Database {} was corrupt, moved it to {}{} and created a new one. Settings and services must be configured again",
            databaseFile, databaseFile, suffix);
        return true;
    }

    private static class CorruptDatabaseException extends SQLException {
        CorruptDatabaseException(String message) {
            super(message);
        }
    }
}
