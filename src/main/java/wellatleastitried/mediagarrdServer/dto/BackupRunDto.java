package wellatleastitried.mediagarrdServer.dto;

import java.util.List;

public record BackupRunDto(
    String runId,
    String startTime,
    String endTime,
    List<String> servicesRan,
    BackupDto archive
) {
}
