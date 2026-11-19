package wellatleastitried.mediagarrdServer.dto;

import java.time.Duration;

public record PublicHealthDto(boolean healthy, Duration backupInterval) {}
