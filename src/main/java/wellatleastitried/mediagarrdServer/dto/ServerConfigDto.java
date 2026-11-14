package wellatleastitried.mediagarrdServer.dto;

import java.time.Duration;

public record ServerConfigDto(String backupLocation, int retentionCount, Duration backupInterval) {}
