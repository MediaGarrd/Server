package wellatleastitried.mediagarrdServer;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;

//@ADD_NEW_SERVICE
public final class MediaGarrdUtilities {
    private MediaGarrdUtilities() {}

    public class Utils {
        public static String recordCurrentTime() {
            Instant instant = Instant.now();
            return formatTime(instant);
        }

        public static String formatTime(Instant instant) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String formattedTime = sdf.format(Date.from(instant));
            return formattedTime;
        }

        public static int subtractTime(String startTime, String endTime) {
        }
        public static long getDurationMs(String startTimeStr, String endTimeStr) {
            try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date startDate = sdf.parse(startTimeStr);
            Date endDate = sdf.parse(endTimeStr);
            return endDate.getTime() - startDate.getTime();
            } catch (ParseException pE) {
            }
        }
    }

    public class ServiceConstants {

        public enum Services {
            JELLYFIN,
            RADARR,
            SONARR,
            PROWLARR,
            TDARR,
            QBITTORRENT
        }

        public static final EnumMap<Services, String> SUPPORTED_SERVICES;

        static {
            SUPPORTED_SERVICES = new EnumMap<>(Services.class);
            SUPPORTED_SERVICES.put(Services.JELLYFIN, "Jellyfin");
            SUPPORTED_SERVICES.put(Services.RADARR, "Radarr");
            SUPPORTED_SERVICES.put(Services.SONARR, "Sonarr");
            SUPPORTED_SERVICES.put(Services.PROWLARR, "Prowlarr");
            SUPPORTED_SERVICES.put(Services.TDARR, "Tdarr");
            SUPPORTED_SERVICES.put(Services.QBITTORRENT, "QBittorrent");
        }
    }

    public class DatabaseConstants {

        // Migrations will be added inside of the static block
        public record Migration(int version, String sql) {}
        public static final List<Migration> MIGRATIONS;
        static {
            MIGRATIONS = new ArrayList<Migration>();
            // Migration versions will start at 2, as the initial tables will be schema v1
        }

        public static final String SQLITE = "jdbc:sqlite:";
        public static final String databaseUrl = "/var/lib/mediagarrd/mediagarrd.db";

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
            filename TEXT,
            file_size INTEGER NOT NULL,
            file_created_time DATETIME,
            error_message TEXT
        );

        CREATE TABLE IF NOT EXISTS backup_services (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            backup_id INTEGER NOT NULL,
            service_name TEXT NOT NULL,
            status TEXT NOT NULL CHECK (status IN ('completed', 'failed')),
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
            services_completed,
            filename,
            file_size,
            file_created_time,
            error_message
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
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
        SELECT id, start_time, status, filename FROM backups
        ORDER BY start_time DESC
        LIMIT 1;
        """;

        public static final String FETCH_SERVICES_FROM_LATEST_RECORD = """
        SELECT service_name, status, start_time FROM backup_services
        WHERE backup_id = ?;
        """;

        public static final String DELETE_BACKUP_RECORD_BY_ID = """
        DELETE FROM backups
        WHERE archive_id = ?;
        """;


    /*
    public record BackupRunResult(
        String runId,
        Instant startedAt,
        Instant finishedAt,
        List<String> servicesRan,
        BackupArchive archive
    )
    public record BackupArchive(
        String id,
        String fileName,
        Path path,
        long sizeBytes,
        Instant createdAt
    )
    */
    }
}
