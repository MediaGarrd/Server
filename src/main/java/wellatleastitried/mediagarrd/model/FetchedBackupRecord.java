package wellatleastitried.mediagarrdServer.model;

import java.util.List;

public record FetchedBackupRecord(
    int id,
    String startTime,
    String status,
    String filename,
    List<FetchedBackupServiceRecord> fetchedServices
) {}
