package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.BACKUP_INTERVAL_SETTING;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;

@Service
public class BackupScheduleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupScheduleService.class);

    private final DatabaseService db;
    private final AtomicReference<Duration> currentInterval;
    private final AtomicReference<Instant> nextRun;

    public BackupScheduleService(MediaGarrdProperties properties, DatabaseService db) {
        this.db = db;
        Duration interval = db.fetchSetting(BACKUP_INTERVAL_SETTING)
            .flatMap(BackupScheduleService::parse)
            .orElseGet(() -> safe(properties.getBackupInterval()));
        this.currentInterval = new AtomicReference<>(interval);
        this.nextRun = new AtomicReference<>(Instant.now().plus(interval));
        LOGGER.info("Backup schedule initialized: interval={}, firstRunAt={}", interval, nextRun.get());
    }

    public Duration getInterval() {
        return currentInterval.get();
    }

    public Instant getNextRun() {
        return nextRun.get();
    }

    public Duration updateInterval(Duration interval) {
        Duration safe = safe(interval);
        db.saveSetting(BACKUP_INTERVAL_SETTING, safe.toString());
        currentInterval.set(safe);
        Instant next = Instant.now().plus(safe);
        nextRun.set(next);
        LOGGER.info("Backup interval updated: newInterval={}, nextRunAt={}", safe, next);
        return safe;
    }

    public boolean dueNow() {
        Instant now = Instant.now();
        Instant current = nextRun.get();
        return !now.isBefore(current);
    }

    public void markRunStarted() {
        Instant next = Instant.now().plus(currentInterval.get());
        nextRun.set(next);
        LOGGER.info("Backup run started: nextScheduledRun={}", next);
    }

    private static Optional<Duration> parse(String stored) {
        try {
            return Optional.of(Duration.parse(stored)).filter(BackupScheduleService::isValid);
        } catch (DateTimeParseException e) {
            LOGGER.warn("Ignoring invalid stored backup interval: {}", stored);
            return Optional.empty();
        }
    }

    private static boolean isValid(Duration interval) {
        return interval != null && !interval.isNegative() && !interval.isZero();
    }

    private static Duration safe(Duration interval) {
        // Default to 12 hours if the provided interval is invalid
        return isValid(interval) ? interval : Duration.ofHours(12);
    }
}
