package wellatleastitried.mediagarrdServer.model;

public class BackupServiceResult {
    private String runId;
    private final String status;
    private final String startTime;
    private final String endTime;
    private final String serviceName;
    private final String errorMessage;

    public BackupServiceResult(
        String runId,
        String status,
        String startTime,
        String endTime,
        String serviceName,
        String errorMessage
    ) {
        this.runId = runId;
        this.status = status;
        this.startTime = startTime;
        this.endTime = endTime;
        this.serviceName = serviceName;
        this.errorMessage = errorMessage;
    }

    public void setId(String runId) {
        this.runId = runId;
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
    public String serviceName() {
        return serviceName;
    }
    public String errorMessage() {
        return errorMessage;
    }
}
