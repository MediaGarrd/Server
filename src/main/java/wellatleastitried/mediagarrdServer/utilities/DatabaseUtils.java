package wellatleastitried.mediagarrdServer.utilities;

import java.util.ArrayList;
import java.util.List;

public class DatabaseUtils {

    public record Migration(int version, String sql) {}
    public static final List<Migration> MIGRATIONS;
    static {
        MIGRATIONS = new ArrayList<Migration>();
        // Migration versions will start at 2, as the initial tables will be schema v1
    }

    public static final String SQLITE = "jdbc:sqlite:";
    public static final String databasePath = "/var/lib/mediagarrd/mediagarrd.db";

    // Status used for the overall backup AND the individual service runners
    public class Status {
        public static final String COMPLETED = "completed";
        public static final String PARTIAL = "partial";
        public static final String FAILED = "failed";
    }

    public static final String CREATE_TABLES = """
    CREATE TABLE IF NOT EXISTS backups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    archive_id TEXT NOT NULL UNIQUE,
    start_time DATETIME NOT NULL,
    end_time DATETIME,
    status TEXT NOT NULL CHECK (status IN ('completed', 'failed', 'partial')),
    checksum TEXT,
    file_path TEXT,
    file_size INTEGER NOT NULL,
    file_created_time DATETIME,
    error_message TEXT
    );

    CREATE TABLE IF NOT EXISTS backup_services (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    backup_id INTEGER NOT NULL,
    service_name TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('completed', 'failed', 'partial')),
    start_time DATETIME,
    end_time DATETIME,
    error_message TEXT,
    FOREIGN KEY (backup_id) REFERENCES backups(id) ON DELETE CASCADE,
    UNIQUE (backup_id, service_name)
    );

    CREATE INDEX IF NOT EXISTS idx_backups_start_time ON backups(start_time);
    CREATE INDEX IF NOT EXISTS idx_backup_services_service_name ON backup_services(service_name);
    """;

    public static final String ADD_BACKUP_RECORD = """
    INSERT INTO backups (
    archive_id,
    start_time,
    end_time,
    status,
    checksum,
    file_path,
    file_size,
    file_created_time,
    error_message
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
    """;

    public static final String ADD_BACKUP_SERVICE_RECORD = """
    INSERT INTO backup_services (
    backup_id,
    service_name,
    status,
    start_time,
    end_time,
    error_message
    ) VALUES (?, ?, ?, ?, ?, ?);
    """;

    public static final String FETCH_LATEST_RECORD = """
    SELECT id, archive_id, start_time, end_time, status, file_path, error_message FROM backups
    ORDER BY start_time DESC
    LIMIT 1;
    """;

    public static final String FETCH_RECORD_BY_ID = """
    SELECT id, archive_id, start_time, end_time, status, file_path, error_message FROM backups
    WHERE archive_id = ?;
    """;

    public static final String FETCH_SERVICES_FROM_LATEST_RECORD = """
    SELECT service_name, status, start_time, end_time, error_message FROM backup_services
    WHERE backup_id = ?;
    """;

    public static final String DELETE_BACKUP_RECORD_BY_ID = """
    DELETE FROM backups
    WHERE archive_id = ?;
    """;
}
