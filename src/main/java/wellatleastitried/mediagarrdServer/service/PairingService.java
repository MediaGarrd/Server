package wellatleastitried.mediagarrdServer.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import wellatleastitried.mediagarrdServer.dto.PairResponse;
import wellatleastitried.mediagarrdServer.dto.PairingCodeDto;
import wellatleastitried.mediagarrdServer.model.PairedClient;
import wellatleastitried.mediagarrdServer.repository.PairedClientRepository;
import wellatleastitried.mediagarrdServer.utilities.LogSanitizer;
import wellatleastitried.mediagarrdServer.utilities.Sha256;

@Service
public class PairingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PairingService.class);

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final Duration CODE_LOCK_DURATION = Duration.ofMinutes(10);
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    static final int MAX_CODE_FAILURES = 10;
    static final Duration PENDING_TTL = Duration.ofMinutes(5);
    static final int MAX_OPEN_CODES = 10;
    static final int MAX_PENDING = 10;
    static final int CODE_LENGTH = 10;
    static final int MAX_TOKEN_LENGTH = 256;
    static final int MAX_CREDENTIAL_LENGTH = 64;
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final Pattern PIN_SHAPE = Pattern.compile("[0-9]{6}");
    private static final Pattern NAME_PATTERN = Pattern.compile("[\\p{L}\\p{N} ._()'@+-]{1,64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SetupService setup;
    private final PairedClientRepository pairedClients;
    private final RunStatusBroadcaster broadcaster;
    private final Clock clock;
    private final AttemptLimiter codeLimiter;
    private final Map<String, Instant> codes = new LinkedHashMap<>();
    private final Map<String, PendingPairing> pending = new LinkedHashMap<>();
    private final Map<String, Instant> lastTouched = new ConcurrentHashMap<>();

    private record PendingPairing(String id, String name, Instant expiresAt, String codeHash, long epoch) {}

    @Autowired
    public PairingService(SetupService setup, PairedClientRepository pairedClients, RunStatusBroadcaster broadcaster) {
        this(setup, pairedClients, broadcaster, Clock.systemUTC());
    }

    PairingService(SetupService setup, PairedClientRepository pairedClients, RunStatusBroadcaster broadcaster, Clock clock) {
        this.setup = setup;
        this.pairedClients = pairedClients;
        this.broadcaster = broadcaster;
        this.clock = clock;
        this.codeLimiter = new AttemptLimiter(MAX_CODE_FAILURES, CODE_LOCK_DURATION, clock);
    }

    public synchronized PairResponse pair(String credential, String requestedName) {
        String name = cleanName(requestedName);
        String value = credential == null ? "" : credential.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("A PIN or pairing code is required");
        }
        if (value.length() > MAX_CREDENTIAL_LENGTH) {
            throw new IllegalArgumentException("The PIN or pairing code is too long");
        }
        if (!setup.isCompleted()) {
            throw new PairingException(PairingException.Reason.SETUP_INCOMPLETE, "This server has not finished setup yet. Finish it on the server's /config page, then pair.");
        }
        if (PIN_SHAPE.matcher(value).matches()) {
            return registerPending(name, null, setup.checkPairingPin(value));
        }
        return registerPending(name, checkCode(value), 0);
    }

    public synchronized PairingCodeDto createCode() {
        Instant now = clock.instant();
        codes.values().removeIf(expiry -> !expiry.isAfter(now));
        while (codes.size() >= MAX_OPEN_CODES) {
            String evicted = codes.keySet().iterator().next();
            codes.remove(evicted);
            pending.values().removeIf(entry -> evicted.equals(entry.codeHash()));
        }
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        Instant expiresAt = now.plus(CODE_TTL);
        codes.put(hashToken(code.toString()), expiresAt);
        LOGGER.info("Pairing code created, it expires at {}", expiresAt);
        return new PairingCodeDto(code.substring(0, CODE_LENGTH / 2) + "-" + code.substring(CODE_LENGTH / 2), expiresAt);
    }

    public Optional<PairedClient> authenticate(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        String tokenHash = hashToken(token);
        Optional<PairedClient> client = pairedClients.fetchPairedClientByTokenHash(tokenHash);
        if (client.isEmpty()) {
            client = confirm(tokenHash);
        }
        client.ifPresent(this::recordUse);
        return client;
    }

    public List<PairedClient> list() {
        return pairedClients.fetchPairedClients();
    }

    public boolean isPaired(String id) {
        return pairedClients.fetchPairedClients().stream().anyMatch(client -> client.id().equals(id));
    }

    public synchronized boolean revoke(String id) {
        boolean removed = pairedClients.removePairedClient(id);
        if (removed) {
            lastTouched.remove(id);
            broadcaster.closeClient(id);
            LOGGER.info("Client {} was unpaired", LogSanitizer.clean(id));
            if (pairedClients.countPairedClients() == 0) {
                LOGGER.info("No Client is paired any more, opening pairing again");
                setup.reopenPairing();
            }
        }
        return removed;
    }

    synchronized void resetAfterRebuild() {
        codes.clear();
        pending.clear();
        lastTouched.clear();
        codeLimiter.reset();
        setup.resetAfterRebuild();
    }

    public static String hashToken(String token) {
        return Sha256.hex(token);
    }

    private synchronized Optional<PairedClient> confirm(String tokenHash) {
        Optional<PairedClient> existing = pairedClients.fetchPairedClientByTokenHash(tokenHash);
        if (existing.isPresent()) {
            return existing;
        }
        PendingPairing entry = pending.get(tokenHash);
        if (entry == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (!entry.expiresAt().isAfter(now)) {
            pending.remove(tokenHash);
            return Optional.empty();
        }
        boolean viaPin = entry.codeHash() == null;
        if (viaPin && !setup.isPairingOpen(entry.epoch())) {
            pending.remove(tokenHash);
            return Optional.empty();
        }
        boolean firstClient = pairedClients.countPairedClients() == 0;
        pairedClients.addPairedClient(entry.id(), entry.name(), tokenHash, now);
        pending.remove(tokenHash);
        if (!viaPin && firstClient) {
            setup.closeCurrentPairing();
            pending.values().removeIf(other -> other.codeHash() == null);
        }
        if (viaPin) {
            setup.closePairing(entry.epoch());
            pending.values().removeIf(other -> other.codeHash() == null);
        } else {
            codes.remove(entry.codeHash());
            pending.values().removeIf(other -> entry.codeHash().equals(other.codeHash()));
        }
        LOGGER.info("Client {} confirmed its pairing ({})", LogSanitizer.clean(entry.name()), entry.id());
        return pairedClients.fetchPairedClientByTokenHash(tokenHash);
    }

    private String checkCode(String candidate) {
        codeLimiter.checkNotLocked();
        String normalized = candidate.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        String codeHash = hashToken(normalized);
        Instant expiry = codes.get(codeHash);
        if (expiry == null || !expiry.isAfter(clock.instant())) {
            int failures = codeLimiter.recordFailure();
            LOGGER.warn("Rejected pairing code attempt ({}/{})", failures, MAX_CODE_FAILURES);
            throw new PairingException(PairingException.Reason.INVALID, "Invalid PIN or pairing code");
        }
        codeLimiter.reset();
        return codeHash;
    }

    private PairResponse registerPending(String requestedName, String codeHash, long epoch) {
        Instant now = clock.instant();
        pending.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
        while (pending.size() >= MAX_PENDING) {
            pending.remove(pending.keySet().iterator().next());
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        byte[] idBytes = new byte[8];
        RANDOM.nextBytes(idBytes);
        String id = HexFormat.of().formatHex(idBytes);
        String name = requestedName != null ? requestedName : "Client " + id.substring(0, 6);
        pending.put(hashToken(token), new PendingPairing(id, name, now.plus(PENDING_TTL), codeHash, epoch));
        LOGGER.info("Client pairing started: {} ({}), it is confirmed by the Client's first request with its token", LogSanitizer.clean(name), id);
        return new PairResponse(token, id, name);
    }

    private void recordUse(PairedClient client) {
        Instant now = clock.instant();
        Instant last = lastTouched.get(client.id());
        if (last != null && now.isBefore(last.plus(TOUCH_INTERVAL))) {
            return;
        }
        lastTouched.put(client.id(), now);
        try {
            pairedClients.touchPairedClient(client.id(), now);
        } catch (IllegalStateException e) {
            LOGGER.debug("Unable to record last use for client {}: {}", client.id(), e.getMessage());
        }
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String stripped = name.strip();
        if (!NAME_PATTERN.matcher(stripped).matches()) {
            throw new IllegalArgumentException("Name must be 1 to 64 characters: letters, digits, spaces and . _ - ( ) ' @ +");
        }
        return stripped;
    }
}
