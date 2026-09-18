package wellatleastitried.mediagarrdServer.dto;

import java.time.Duration;

public record ScheduleUpdateRequest(Duration backupInterval) {}
