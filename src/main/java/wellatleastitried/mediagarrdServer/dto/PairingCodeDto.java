package wellatleastitried.mediagarrdServer.dto;

import java.time.Instant;

public record PairingCodeDto(String code, Instant expiresAt) {}
