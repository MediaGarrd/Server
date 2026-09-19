package wellatleastitried.mediagarrdServer.model;

public record FetchedBackupServiceRecord(
    String serviceName,
    String status,
    String startTime
) {}
