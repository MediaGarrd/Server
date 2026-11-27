package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.BACKUP_INTERVAL_SETTING;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.repository.BackupRecordRepository;
import wellatleastitried.mediagarrdServer.repository.SettingsRepository;
import wellatleastitried.mediagarrdServer.utilities.LogSanitizer;

@Service
public class BackupScheduleService {

    public static final Duration MIN_INTERVAL = Duration.ofMinutes(15);
    public static final Duration MAX_INTERVAL = Duration.ofDays(365);
    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(12);
    private static final Logger LOGGER = LoggerFactory.getLogger(BackupScheduleService.class);

    private final MediaGarrdProperties properties;
    private final SettingsRepository settings;
    private final Clock clock;
    private final AtomicReference<Duration> currentInterval;
    private final AtomicReference<Instant> nextRun;
    private final AtomicBoolean deferred = new AtomicBoolean();
    private final AtomicBoolean awaitingSetup = new AtomicBoolean();
    private final AtomicReference<Instant> lastRunStarted = new AtomicReference<>();
    private final BackupRecordRepository backups;
    private final SetupService setup;

    @Autowired
    public BackupScheduleService(MediaGarrdProperties properties, SettingsRepository settings, BackupRecordRepository backups, SetupService setup) {
        this(properties, settings, backups, setup, Clock.systemUTC());
    }

    BackupScheduleService(MediaGarrdProperties properties, SettingsRepository settings, BackupRecordRepository backups, SetupService setup, Clock clock) {
        this.properties = properties;
        this.settings = settings;
        this.backups = backups;
        this.setup = setup;
        this.clock = clock;
        Duration interval = storedOrConfiguredInterval();
        this.currentInterval = new AtomicReference<>(interval);
        this.nextRun = new AtomicReference<>(computeNextRun(interval));
        LOGGER.info("Backup schedule initialized: interval={}, firstRunAt={}", interval, nextRun.get());
    }

    private Duration storedOrConfiguredInterval() {
        return settings.fetchSetting(BACKUP_INTERVAL_SETTING)
            .flatMap(BackupScheduleService::parse)
            .orElseGet(() -> fromProperties(properties.getBackupInterval()));
    }

    public void reloadFromSettings() {
        Duration interval = storedOrConfiguredInterval();
        Instant next = computeNextRun(interval);
        currentInterval.set(interval);
        nextRun.set(next);
        deferred.set(false);
        LOGGER.info("Backup schedule reloaded from the settings: interval={}, nextRunAt={}", interval, next);
    }

    public Duration getInterval() {
        return currentInterval.get();
    }

    public Instant getNextRun() {
        return nextRun.get();
    }

    public Duration updateInterval(Duration interval) {
        validate(interval);
        Instant next = computeNextRun(interval);
        settings.saveSetting(BACKUP_INTERVAL_SETTING, interval.toString());
        currentInterval.set(interval);
        nextRun.set(next);
        deferred.set(false);
        LOGGER.info("Backup interval updated: newInterval={}, nextRunAt={}", interval, next);
        return interval;
    }

    public boolean dueNow() {
        Instant now = clock.instant();
        if (awaitingSetup.get() && setup.isCompleted() && awaitingSetup.compareAndSet(true, false)) {
            nextRun.set(now);
            LOGGER.info("Setup is completed, the first scheduled backup is due now");
        }
        Instant current = nextRun.get();
        return !now.isBefore(current);
    }

    public void markRunStarted() {
        lastRunStarted.set(clock.instant());
        Instant next = clock.instant().plus(currentInterval.get());
        nextRun.set(next);
        awaitingSetup.set(false);
        deferred.set(false);
        LOGGER.info("Backup run started: nextScheduledRun={}", next);
    }

    public void deferNextRun() {
        Instant next = clock.instant().plus(currentInterval.get());
        nextRun.set(next);
        awaitingSetup.set(false);
        deferred.set(true);
        LOGGER.info("Nothing to back up yet, next scheduled check at {}", next);
    }

    public void releaseDeferral() {
        if (deferred.compareAndSet(true, false)) {
            nextRun.set(clock.instant());
            LOGGER.info("Services are configured, the next scheduled backup is due now");
        }
    }

    private Instant computeNextRun(Duration interval) {
        Instant now = clock.instant();
        awaitingSetup.set(false);
        Optional<Instant> recorded;
        try {
            recorded = backups.fetchLastSuccessfulStartTime();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the last successful backup, the next scheduled backup is one interval from now", e);
            return now.plus(interval);
        }
        Optional<Instant> last = recorded.map(this::laterThanThisRun).or(() -> Optional.ofNullable(lastRunStarted.get()));
        if (last.isPresent()) {
            Instant due = last.get().plus(interval);
            return due.isAfter(now) && !last.get().isAfter(now) ? due : now;
        }
        if (setup.isCompleted()) {
            return now;
        }
        awaitingSetup.set(true);
        return now.plus(interval);
    }

    private Instant laterThanThisRun(Instant recorded) {
        Instant started = lastRunStarted.get();
        return started != null && started.isAfter(recorded) && !started.isAfter(clock.instant()) ? started : recorded;
    }

    private static Optional<Duration> parse(String stored) {
        try {
            Duration interval = Duration.parse(stored);
            if (!inRange(interval)) {
                LOGGER.warn("Ignoring out-of-range stored backup interval: {}", LogSanitizer.clean(stored));
                return Optional.empty();
            }
            return Optional.of(interval);
        } catch (DateTimeParseException e) {
            LOGGER.warn("Ignoring invalid stored backup interval: {}", LogSanitizer.clean(stored));
            return Optional.empty();
        }
    }

    private static Duration fromProperties(Duration configured) {
        if (!inRange(configured)) {
            LOGGER.warn("Ignoring out-of-range MEDIAGARRD_BACKUP_INTERVAL {}, using {}", configured, DEFAULT_INTERVAL);
            return DEFAULT_INTERVAL;
        }
        return configured;
    }

    private static boolean inRange(Duration interval) {
        return interval != null && interval.compareTo(MIN_INTERVAL) >= 0 && interval.compareTo(MAX_INTERVAL) <= 0;
    }

    private static void validate(Duration interval) {
        if (!inRange(interval)) {
            throw new IllegalArgumentException("Backup interval must be between 15 minutes and 365 days");
        }
    }
}
