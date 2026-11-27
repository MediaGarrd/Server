package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.BACKUP_INTERVAL_SETTING;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.SETUP_COMPLETED_SETTING;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.PARTIAL;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.repository.BackupRecordRepository;
import wellatleastitried.mediagarrdServer.repository.TestDatabase;
import wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils;

class BackupScheduleServiceTest {

    @TempDir
    Path tempDir;

    private MediaGarrdProperties properties;
    private TestDatabase db;
    private MutableClock clock;
    private SetupService setup;

    @BeforeEach
    void setUp() {
        properties = new MediaGarrdProperties();
        properties.setDatabasePath(tempDir.resolve("mediagarrd.db").toString());
        db = new TestDatabase(properties);
        clock = new MutableClock();
        setup = newSetup();
    }

    private BackupScheduleService service() {
        return new BackupScheduleService(properties, db.settings, db.backups, setup, clock);
    }

    private void recordBackup(String id, String status, Instant startedAt) {
        String start = MediaGarrdUtils.formatTime(startedAt);
        BackupArchive archive = "failed".equals(status) ? null
            : new BackupArchive(id, id + ".zip", Path.of(id + ".zip"), 0, startedAt);
        db.backups.addNewBackupRecord(new BackupRunResult(id, start, start, status, List.of(), archive, null));
    }

    private void completeSetup() {
        db.settings.saveSetting(SETUP_COMPLETED_SETTING, "2026-11-19 09:00:00");
        setup = newSetup();
    }

    private SetupService newSetup() {
        return new SetupService(db.settings, db.pairedClients, false, clock);
    }

    @Test
    void updatesAndEvaluatesDueState() {
        BackupScheduleService service = service();
        service.updateInterval(Duration.ofMinutes(15));

        assertEquals(Duration.ofMinutes(15), service.getInterval());
        assertFalse(service.dueNow());

        clock.advance(Duration.ofMinutes(15));

        assertTrue(service.dueNow());
    }

    @Test
    void notDueUntilIntervalElapses() {
        properties.setBackupInterval(Duration.ofHours(1));
        BackupScheduleService service = service();

        assertFalse(service.dueNow());

        clock.advance(Duration.ofMinutes(59));
        assertFalse(service.dueNow());

        clock.advance(Duration.ofMinutes(1));
        assertTrue(service.dueNow());
    }

    @Test
    void persistsUpdatedIntervalAcrossRestarts() {
        properties.setBackupInterval(Duration.ofHours(3));
        BackupScheduleService first = service();
        assertEquals(Duration.ofHours(3), first.getInterval());

        first.updateInterval(Duration.ofMinutes(45));

        assertEquals(Optional.of("PT45M"), db.settings.fetchSetting(BACKUP_INTERVAL_SETTING));
        assertEquals(Duration.ofMinutes(45), service().getInterval());
    }

    @Test
    void aRestartRunsAnOverdueBackupRightAway() {
        completeSetup();
        properties.setBackupInterval(Duration.ofHours(12));
        recordBackup("run-1", COMPLETED, clock.instant().minus(Duration.ofHours(13)));

        BackupScheduleService restarted = service();

        assertTrue(restarted.dueNow());
        assertEquals(clock.instant(), restarted.getNextRun());
    }

    @Test
    void aRestartKeepsTheOriginalDueTimeWhenNotYetDue() {
        completeSetup();
        properties.setBackupInterval(Duration.ofHours(12));
        Instant last = clock.instant().minus(Duration.ofHours(5));
        recordBackup("run-1", PARTIAL, last);

        BackupScheduleService restarted = service();

        assertFalse(restarted.dueNow());
        assertEquals(last.plus(Duration.ofHours(12)), restarted.getNextRun());
        clock.advance(Duration.ofHours(7));
        assertTrue(restarted.dueNow());
    }

    @Test
    void aSuccessfulBackupAnchorsEvenWhenSetupIsNotCompleted() {
        properties.setBackupInterval(Duration.ofHours(12));
        recordBackup("run-1", COMPLETED, clock.instant().minus(Duration.ofHours(13)));

        assertTrue(service().dueNow());
    }

    @Test
    void noBackupEverIsDueNowOnceSetupIsCompleted() {
        completeSetup();

        assertTrue(service().dueNow());
    }

    @Test
    void failedBackupsDoNotCount() {
        completeSetup();
        properties.setBackupInterval(Duration.ofHours(12));
        recordBackup("failed-1", FAILED, clock.instant().minus(Duration.ofHours(1)));

        assertTrue(service().dueNow());
    }

    @Test
    void aFailedBackupAfterASuccessfulOneIsIgnored() {
        completeSetup();
        properties.setBackupInterval(Duration.ofHours(12));
        Instant good = clock.instant().minus(Duration.ofHours(2));
        recordBackup("run-1", COMPLETED, good);
        recordBackup("failed-1", FAILED, clock.instant().minus(Duration.ofHours(1)));

        assertEquals(good.plus(Duration.ofHours(12)), service().getNextRun());
    }

    @Test
    void noBackupEverWaitsAnIntervalWhileSetupIsNotCompleted() {
        properties.setBackupInterval(Duration.ofHours(12));

        BackupScheduleService service = service();

        assertFalse(service.dueNow());
        assertEquals(clock.instant().plus(Duration.ofHours(12)), service.getNextRun());
    }

    @Test
    void completingSetupMakesTheFirstBackupDue() {
        properties.setBackupInterval(Duration.ofHours(12));
        BackupScheduleService service = service();
        assertFalse(service.dueNow());

        setup.complete();

        assertTrue(service.dueNow());
    }

    @Test
    void aBackupDateInTheFutureIsDueNow() {
        completeSetup();
        properties.setBackupInterval(Duration.ofHours(12));
        recordBackup("run-1", COMPLETED, clock.instant().plus(Duration.ofDays(3)));

        BackupScheduleService restarted = service();

        assertTrue(restarted.dueNow());
        assertEquals(clock.instant(), restarted.getNextRun());
    }

    @Test
    void changingTheIntervalRecomputesFromTheLastBackup() {
        completeSetup();
        properties.setBackupInterval(Duration.ofHours(12));
        Instant last = clock.instant().minus(Duration.ofHours(5));
        recordBackup("run-1", COMPLETED, last);
        BackupScheduleService service = service();

        service.updateInterval(Duration.ofHours(24));
        assertEquals(last.plus(Duration.ofHours(24)), service.getNextRun());
        assertFalse(service.dueNow());

        service.updateInterval(Duration.ofHours(2));
        assertEquals(clock.instant(), service.getNextRun());
        assertTrue(service.dueNow());
    }

    @Test
    void changingTheIntervalWithoutABackupFollowsSetupState() {
        BackupScheduleService beforeSetup = service();
        beforeSetup.updateInterval(Duration.ofHours(2));
        assertEquals(clock.instant().plus(Duration.ofHours(2)), beforeSetup.getNextRun());

        completeSetup();
        BackupScheduleService afterSetup = service();
        afterSetup.updateInterval(Duration.ofHours(2));
        assertEquals(clock.instant(), afterSetup.getNextRun());
    }

    @Test
    void changingTheIntervalDuringARunDoesNotMakeAnotherRunDue() {
        completeSetup();
        properties.setBackupInterval(Duration.ofHours(12));
        recordBackup("run-1", COMPLETED, clock.instant().minus(Duration.ofHours(13)));
        BackupScheduleService service = service();
        assertTrue(service.dueNow());
        service.markRunStarted();

        service.updateInterval(Duration.ofHours(6));

        assertFalse(service.dueNow());
        assertEquals(clock.instant().plus(Duration.ofHours(6)), service.getNextRun());
    }

    @Test
    void changingTheIntervalAfterAFailedRunWaitsAFullInterval() {
        completeSetup();
        properties.setBackupInterval(Duration.ofHours(12));
        BackupScheduleService service = service();
        service.markRunStarted();
        clock.advance(Duration.ofMinutes(10));

        service.updateInterval(Duration.ofHours(1));

        assertFalse(service.dueNow());
        assertEquals(clock.instant().plus(Duration.ofMinutes(50)), service.getNextRun());
    }

    @Test
    void aNewerRecordedBackupWinsOverAnEarlierRunStart() {
        completeSetup();
        properties.setBackupInterval(Duration.ofHours(12));
        BackupScheduleService service = service();
        service.markRunStarted();
        clock.advance(Duration.ofHours(1));
        Instant recorded = clock.instant();
        recordBackup("run-1", COMPLETED, recorded);

        service.updateInterval(Duration.ofHours(3));

        assertEquals(recorded.plus(Duration.ofHours(3)), service.getNextRun());
    }

    @Test
    void anUnreadableBackupHistoryFallsBackToAFullInterval() {
        completeSetup();
        properties.setBackupInterval(Duration.ofHours(12));
        BackupRecordRepository broken = mock(BackupRecordRepository.class);
        when(broken.fetchLastSuccessfulStartTime()).thenThrow(new IllegalStateException("database unavailable"));

        BackupScheduleService service = new BackupScheduleService(properties, db.settings, broken, setup, clock);

        assertFalse(service.dueNow());
        assertEquals(clock.instant().plus(Duration.ofHours(12)), service.getNextRun());
    }

    @Test
    void outOfRangeIntervalsAreRejectedAndChangeNothing() {
        BackupScheduleService service = service();
        service.updateInterval(Duration.ofHours(3));
        Instant nextRun = service.getNextRun();
        String message = "Backup interval must be between 15 minutes and 365 days";

        for (Duration bad : new Duration[] {null, Duration.ZERO, Duration.ofMinutes(-5), Duration.ofMinutes(15).minusSeconds(1),
                Duration.ofSeconds(1), Duration.ofDays(365).plusSeconds(1), Duration.ofMinutes(600000000000000L), Duration.ofSeconds(Long.MAX_VALUE)}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.updateInterval(bad));
            assertEquals(message, e.getMessage());
        }

        assertEquals(Duration.ofHours(3), service.getInterval());
        assertEquals(nextRun, service.getNextRun());
        assertEquals(Duration.ofHours(3).toString(), db.settings.fetchSetting(BACKUP_INTERVAL_SETTING).orElseThrow());
    }

    @Test
    void boundaryIntervalsAreAccepted() {
        BackupScheduleService service = service();

        assertEquals(Duration.ofMinutes(15), service.updateInterval(Duration.ofMinutes(15)));
        assertEquals(Duration.ofDays(365), service.updateInterval(Duration.ofDays(365)));
        service.markRunStarted();
        service.deferNextRun();
        assertEquals(clock.instant().plus(Duration.ofDays(365)), service.getNextRun());
        assertEquals(Duration.ofDays(365).toString(), db.settings.fetchSetting(BACKUP_INTERVAL_SETTING).orElseThrow());
    }

    @Test
    void ignoresOutOfRangeStoredIntervalAndStartsWithARecordedBackup() {
        properties.setBackupInterval(Duration.ofHours(2));
        recordBackup("last", COMPLETED, clock.instant().minus(Duration.ofHours(1)));

        for (String stored : new String[] {"PT600000000000000M", "PT14M", "P366D", "PT1S"}) {
            db.settings.saveSetting(BACKUP_INTERVAL_SETTING, stored);
            BackupScheduleService service = service();
            assertEquals(Duration.ofHours(2), service.getInterval());
            assertEquals(clock.instant().plus(Duration.ofHours(1)), service.getNextRun());
        }
    }

    @Test
    void outOfRangeEnvironmentDefaultFallsBackToTwelveHours() {
        recordBackup("last", COMPLETED, clock.instant().minus(Duration.ofHours(1)));

        for (Duration bad : new Duration[] {Duration.ofMinutes(5), Duration.ofDays(400), Duration.ofMinutes(600000000000000L), Duration.ZERO, null}) {
            properties.setBackupInterval(bad);
            BackupScheduleService service = service();
            assertEquals(Duration.ofHours(12), service.getInterval());
            assertEquals(clock.instant().plus(Duration.ofHours(11)), service.getNextRun());
        }
    }

    @Test
    void acceptsBoundaryValuesFromStorageAndEnvironment() {
        db.settings.saveSetting(BACKUP_INTERVAL_SETTING, "PT15M");
        assertEquals(Duration.ofMinutes(15), service().getInterval());
        db.settings.saveSetting(BACKUP_INTERVAL_SETTING, "P365D");
        assertEquals(Duration.ofDays(365), service().getInterval());

        db.settings.saveSetting(BACKUP_INTERVAL_SETTING, "junk");
        properties.setBackupInterval(Duration.ofDays(365));
        assertEquals(Duration.ofDays(365), service().getInterval());
    }

    @Test
    void ignoresCorruptStoredInterval() {
        properties.setBackupInterval(Duration.ofHours(2));
        db.settings.saveSetting(BACKUP_INTERVAL_SETTING, "every tuesday");
        assertEquals(Duration.ofHours(2), service().getInterval());

        db.settings.saveSetting(BACKUP_INTERVAL_SETTING, "PT0S");
        assertEquals(Duration.ofHours(2), service().getInterval());
    }

    @Test
    void deferringTheNextRunStopsItBeingDueUntilTheIntervalElapses() {
        BackupScheduleService service = service();
        service.updateInterval(Duration.ofMinutes(15));
        clock.advance(Duration.ofMinutes(15));
        assertTrue(service.dueNow());

        service.updateInterval(Duration.ofHours(1));
        clock.advance(Duration.ofMinutes(10));
        service.deferNextRun();

        assertFalse(service.dueNow());
        Instant expected = clock.instant().plus(Duration.ofHours(1));
        assertEquals(expected, service.getNextRun());

        clock.advance(Duration.ofHours(1));
        assertTrue(service.dueNow());
    }

    @Test
    void addingAServiceAfterADeferralMakesTheScheduleDueNow() {
        BackupScheduleService service = service();
        service.deferNextRun();
        assertFalse(service.dueNow());

        service.releaseDeferral();

        assertTrue(service.dueNow());
    }

    @Test
    void releasingWithoutADeferralChangesNothing() {
        BackupScheduleService service = service();
        Instant before = service.getNextRun();

        service.releaseDeferral();

        assertEquals(before, service.getNextRun());
        assertFalse(service.dueNow());
    }
}
