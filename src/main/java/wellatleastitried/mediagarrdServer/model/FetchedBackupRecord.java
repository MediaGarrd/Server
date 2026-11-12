package wellatleastitried.mediagarrdServer.model;

import java.util.List;

public record FetchedBackupRecord(
    int id,
    String archiveId,
    String startTime,
    String endTime,
    String status,
    String filePath,
    List<FetchedBackupServiceRecord> fetchedServices,
    String errorMessage
) {}
