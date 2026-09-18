package wellatleastitried.mediagarrdServer.model;

import java.util.List;

public class BackupRunResult {
    private final String runId;
    private final String startTime;
    private final String endTime;
    private final String status;
    private final List<BackupServiceResult> serviceResults;
    private final BackupArchive archive;
    private final String errorMessage;

    public BackupRunResult(
        String runId,
        String startTime,
        String endTime,
        String status,
        List<BackupServiceResult> serviceResults,
        BackupArchive archive,
        String errorMessage
    ) {
        this.runId = runId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = status;
        this.serviceResults = serviceResults;
        this.archive = archive;
        this.errorMessage = errorMessage;
    }

    public String runId() {
        return runId;
    }

    public String status() {
        return status;
    }

    public String startTime() {
        return startTime;
    }

    public String endTime() {
        return endTime;
    }

    public List<BackupServiceResult> serviceResults() {
        return serviceResults;
    }

    public List<String> serviceNames() {
        return serviceResults.stream().map(BackupServiceResult::serviceName).toList();
    }

    public BackupArchive archive() {
        return archive;
    }

    public String errorMessage() {
        return errorMessage;
    }
}
