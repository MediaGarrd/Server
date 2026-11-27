package wellatleastitried.mediagarrdServer.model;

import java.time.Instant;

public record PairedClient(String id, String name, Instant createdAt, Instant lastUsedAt) {}
