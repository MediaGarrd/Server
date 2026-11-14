package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.recordCurrentTime;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.SETUP_COMPLETED_SETTING;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Lazy(false)
public class SetupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SetupService.class);

    static final int MAX_FAILED_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatabaseService db;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private volatile boolean completed;
    private String pin;
    private int failedAttempts;

    public SetupService(DatabaseService db) {
        this.db = db;
        this.completed = db.fetchSetting(SETUP_COMPLETED_SETTING).isPresent();
        if (completed) {
            LOGGER.info("Setup already completed, the web setup page is disabled");
        } else {
            rotatePin();
        }
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean canConfigure(String sessionToken) {
        return completed || (sessionToken != null && sessions.contains(sessionToken));
    }

    public synchronized Optional<String> startSession(String candidate) {
        if (completed) {
            return Optional.empty();
        }
        if (candidate == null || !MessageDigest.isEqual(bytes(pin), bytes(candidate.strip()))) {
            failedAttempts++;
            LOGGER.warn("Rejected setup PIN attempt ({}/{})", failedAttempts, MAX_FAILED_ATTEMPTS);
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                rotatePin();
            }
            return Optional.empty();
        }
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        String session = HexFormat.of().formatHex(token);
        sessions.add(session);
        LOGGER.info("Setup PIN accepted, a new PIN is required for any other device");
        rotatePin();
        return Optional.of(session);
    }

    public synchronized void complete() {
        db.saveSetting(SETUP_COMPLETED_SETTING, recordCurrentTime());
        completed = true;
        sessions.clear();
        pin = null;
        LOGGER.info("Setup completed, the web setup page is now disabled");
    }

    synchronized String currentPin() {
        return pin;
    }

    private void rotatePin() {
        pin = "%06d".formatted(RANDOM.nextInt(1_000_000));
        failedAttempts = 0;
        LOGGER.warn("Setup PIN: {} (single use, enter it at http://<this-server>:<port>/config)", pin);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
