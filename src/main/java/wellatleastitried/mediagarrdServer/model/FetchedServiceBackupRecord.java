package wellatleastitried.mediagarrdServer.model;

import java.util.List;
        /*
        public static final String FETCH_LATEST_RECORD = """
        SELECT id, start_time, status, filename FROM backups
        ORDER BY start_time DESC
        LIMIT 1;
        """;

        public static final String FETCH_SERVICES_FROM_LATEST_RECORD = """
        SELECT service_name, status, start_time FROM backup_services
        WHERE backup_id = ?;
        """;
        */
public record FetchedBackupServiceRecord(
    String runId,
    String startTime,
    String endTime,
    String status,
    List<BackupServiceResult> serviceResults,
    BackupArchive archive,
    String errorMessage
) {}
