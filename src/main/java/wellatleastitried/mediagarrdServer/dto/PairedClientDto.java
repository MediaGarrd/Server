package wellatleastitried.mediagarrdServer.dto;

import java.time.Instant;

public record PairedClientDto(String id, String name, Instant createdAt, Instant lastUsedAt, boolean current) {}
