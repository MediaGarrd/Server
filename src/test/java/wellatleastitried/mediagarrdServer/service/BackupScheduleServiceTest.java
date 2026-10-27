package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.BACKUP_INTERVAL_SETTING;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;

class BackupScheduleServiceTest {

    @TempDir
    Path tempDir;

    private MediaGarrdProperties properties;
    private DatabaseService db;

    @BeforeEach
    void setUp() {
        properties = new MediaGarrdProperties();
        properties.setDatabasePath(tempDir.resolve("mediagarrd.db").toString());
        db = new DatabaseService(properties);
    }

    @Test
    void updatesAndEvaluatesDueState() throws Exception {
        BackupScheduleService service = new BackupScheduleService(properties, db);
        service.updateInterval(Duration.ofMillis(1));

        assertEquals(Duration.ofMillis(1), service.getInterval());

        Thread.sleep(5);

        assertTrue(service.dueNow());
    }

    @Test
    void notDueUntilIntervalElapses() {
        BackupScheduleService service = new BackupScheduleService(properties, db);

        assertFalse(service.dueNow());
    }

    @Test
    void usesPropertyIntervalUntilOneIsSaved() {
        properties.setBackupInterval(Duration.ofHours(3));

        assertEquals(Duration.ofHours(3), new BackupScheduleService(properties, db).getInterval());
    }

    @Test
    void persistsUpdatedIntervalAcrossRestarts() {
        properties.setBackupInterval(Duration.ofHours(3));
        new BackupScheduleService(properties, db).updateInterval(Duration.ofMinutes(45));

        assertEquals(Optional.of("PT45M"), db.fetchSetting(BACKUP_INTERVAL_SETTING));
        assertEquals(Duration.ofMinutes(45), new BackupScheduleService(properties, db).getInterval());
    }

    @Test
    void invalidIntervalsFallBackToTwelveHours() {
        BackupScheduleService service = new BackupScheduleService(properties, db);

        assertEquals(Duration.ofHours(12), service.updateInterval(null));
        assertEquals(Duration.ofHours(12), service.updateInterval(Duration.ZERO));
        assertEquals(Duration.ofHours(12), service.updateInterval(Duration.ofMinutes(-5)));
    }

    @Test
    void ignoresCorruptStoredInterval() {
        properties.setBackupInterval(Duration.ofHours(2));
        db.saveSetting(BACKUP_INTERVAL_SETTING, "every tuesday");
        assertEquals(Duration.ofHours(2), new BackupScheduleService(properties, db).getInterval());

        db.saveSetting(BACKUP_INTERVAL_SETTING, "PT0S");
        assertEquals(Duration.ofHours(2), new BackupScheduleService(properties, db).getInterval());
    }
}
