package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.recordCurrentTime;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.SETUP_COMPLETED_SETTING;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.repository.PairedClientRepository;
import wellatleastitried.mediagarrdServer.repository.SettingsRepository;

@Service
@Lazy(false)
public class SetupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SetupService.class);

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(10);
    static final Duration SESSION_IDLE_TIMEOUT = Duration.ofMinutes(30);
    static final int MAX_PIN_LENGTH = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SettingsRepository settings;
    private final PairedClientRepository pairedClients;
    private final AttemptLimiter limiter;
    private final Clock clock;
    private final boolean openPairingOnStart;
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private volatile boolean completed;
    private boolean pairingOpen;
    private long pairingEpoch;
    private String pin;

    @Autowired
    public SetupService(SettingsRepository settings, PairedClientRepository pairedClients, MediaGarrdProperties properties) {
        this(settings, pairedClients, properties.isOpenPairing(), Clock.systemUTC());
    }

    SetupService(SettingsRepository settings, PairedClientRepository pairedClients, boolean openPairing, Clock clock) {
        this.settings = settings;
        this.pairedClients = pairedClients;
        this.clock = clock;
        this.limiter = new AttemptLimiter(MAX_FAILED_ATTEMPTS, LOCK_DURATION, clock);
        this.openPairingOnStart = openPairing;
        applyStoredState();
    }

    synchronized void resetAfterRebuild() {
        sessions.clear();
        limiter.reset();
        pairingOpen = false;
        pairingEpoch++;
        pin = null;
        applyStoredState();
        if (!completed) {
            LOGGER.warn("The database was damaged and has been replaced with an empty one. Setup is open again with the Setup PIN logged above: "
                + "enter it at /config to configure the server again, or restore an archive first. Paired Clients must pair again");
        } else {
            LOGGER.warn("The database was damaged and has been replaced. Clients that paired before must pair again");
        }
    }

    private void applyStoredState() {
        this.completed = settings.fetchSetting(SETUP_COMPLETED_SETTING).isPresent();
        if (!completed) {
            rotatePin();
        } else if (openPairingOnStart) {
            LOGGER.warn("MEDIAGARRD_OPEN_PAIRING is set, pairing is open on every start while it is set. Remove it once the Client is paired");
            openPairing();
        } else if (pairedClients.countPairedClients() == 0) {
            LOGGER.info("Setup is completed but no Client is paired");
            openPairing();
        } else {
            LOGGER.info("Setup already completed, the web setup page is disabled and pairing is closed. "
                + "Request a pairing code from a paired Client, or restart with MEDIAGARRD_OPEN_PAIRING=true to print a new PIN");
        }
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean canConfigure(String sessionToken) {
        if (completed || sessionToken == null) {
            return false;
        }
        Instant now = clock.instant();
        return sessions.computeIfPresent(sessionToken, (token, lastSeen) -> expired(lastSeen, now) ? null : now) != null;
    }

    public synchronized boolean isPairingOpen() {
        return pairingOpen;
    }

    public synchronized Optional<String> startSession(String candidate) {
        if (completed) {
            return Optional.empty();
        }
        if (candidate != null && candidate.length() > MAX_PIN_LENGTH) {
            throw new IllegalArgumentException("PIN is too long");
        }
        limiter.checkNotLocked();
        if (!pinMatches(candidate)) {
            rejectPin("setup");
            return Optional.empty();
        }
        limiter.reset();
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        String session = HexFormat.of().formatHex(token);
        Instant now = clock.instant();
        sessions.values().removeIf(lastSeen -> expired(lastSeen, now));
        sessions.put(session, now);
        LOGGER.info("Setup PIN accepted, a new PIN is required for any other device");
        rotatePin();
        return Optional.of(session);
    }

    public synchronized String complete() {
        if (completed) {
            throw new SetupAlreadyCompleteException();
        }
        settings.saveSetting(SETUP_COMPLETED_SETTING, recordCurrentTime());
        completed = true;
        sessions.clear();
        limiter.reset();
        LOGGER.info("Setup completed, the web setup page is now disabled");
        if (pairedClients.countPairedClients() == 0) {
            openPairing();
        } else {
            pin = null;
        }
        return pin;
    }

    synchronized long checkPairingPin(String candidate) {
        if (!completed) {
            throw new PairingException(PairingException.Reason.SETUP_INCOMPLETE, "This server has not finished setup yet. Finish it on the server's /config page, then pair.");
        }
        limiter.checkNotLocked();
        if (!pairingOpen) {
            throw new PairingException(PairingException.Reason.CLOSED,
                "Pairing is closed. Ask a paired Client for a pairing code, or restart the server with MEDIAGARRD_OPEN_PAIRING=true");
        }
        if (!pinMatches(candidate)) {
            rejectPin("pairing");
            throw new PairingException(PairingException.Reason.INVALID, "Invalid PIN or pairing code");
        }
        LOGGER.info("Pairing PIN accepted, pairing closes once the Client has used its token");
        return pairingEpoch;
    }

    synchronized boolean isPairingOpen(long epoch) {
        return pairingOpen && pairingEpoch == epoch;
    }

    synchronized boolean closePairing(long epoch) {
        if (!isPairingOpen(epoch)) {
            return false;
        }
        limiter.reset();
        pairingOpen = false;
        pin = null;
        LOGGER.info("Pairing is now closed");
        return true;
    }

    synchronized boolean closeCurrentPairing() {
        return closePairing(pairingEpoch);
    }

    synchronized void reopenPairing() {
        if (completed && !pairingOpen) {
            openPairing();
        }
    }

    synchronized String currentPin() {
        return pin;
    }

    private void openPairing() {
        pairingOpen = true;
        pairingEpoch++;
        rotatePin();
    }

    private static boolean expired(Instant lastSeen, Instant now) {
        return !lastSeen.plus(SESSION_IDLE_TIMEOUT).isAfter(now);
    }

    private void rejectPin(String purpose) {
        int failures = limiter.recordFailure();
        LOGGER.warn("Rejected {} PIN attempt ({}/{})", purpose, failures, MAX_FAILED_ATTEMPTS);
        if (limiter.isLockedNow()) {
            LOGGER.warn("PIN entry is locked for {} minutes after too many wrong attempts", LOCK_DURATION.toMinutes());
            rotatePin();
        }
    }

    private boolean pinMatches(String candidate) {
        return pin != null && candidate != null && MessageDigest.isEqual(bytes(pin), bytes(candidate.strip()));
    }

    private void rotatePin() {
        pin = "%06d".formatted(RANDOM.nextInt(1_000_000));
        if (completed) {
            LOGGER.warn("Pairing PIN: {} (enter it in the MediaGarrd Client, it stops working once a Client has paired)", pin);
        } else {
            LOGGER.warn("Setup PIN: {} (single use, enter it at http://<this-server>:<port>/config)", pin);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
