package wellatleastitried.mediagarrdServer.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.PARTIAL;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.FetchedBackupRecord;
import wellatleastitried.mediagarrdServer.model.FetchedBackupServiceRecord;

class BackupRecordRepositoryTest extends DatabaseTestBase {

    @Test
    void storesBackupRecordWithEveryServiceResult() {
        db.backups.addNewBackupRecord(backupRun("run-1", "2026-01-01 00:00:00",
            serviceResult("jellyfin", COMPLETED, null),
            serviceResult("sonarr", PARTIAL, "one path failed"),
            serviceResult("qbittorrent", FAILED, "no paths copied")));

        FetchedBackupRecord record = db.backups.fetchBackupById("run-1").orElseThrow();

        assertEquals("run-1", record.archiveId());
        assertEquals("2026-01-01 00:00:00", record.startTime());
        assertEquals(COMPLETED, record.status());
        assertEquals(Path.of("run-1.zip").toString(), record.filePath());
        assertEquals(
            Set.of(
                new FetchedBackupServiceRecord("jellyfin", COMPLETED, "2026-01-01 00:00:00", "2026-01-01 00:00:01", null),
                new FetchedBackupServiceRecord("sonarr", PARTIAL, "2026-01-01 00:00:00", "2026-01-01 00:00:01", "one path failed"),
                new FetchedBackupServiceRecord("qbittorrent", FAILED, "2026-01-01 00:00:00", "2026-01-01 00:00:01", "no paths copied")),
            Set.copyOf(record.fetchedServices()));
    }

    @Test
    void storesFailedRunWithoutArchive() {
        db.backups.addNewBackupRecord(new BackupRunResult("failed-1", "2026-01-01 00:00:00", "2026-01-01 00:00:01", FAILED,
            List.of(serviceResult("radarr", FAILED, "boom")), null, "radarr: boom"));

        FetchedBackupRecord record = db.backups.fetchBackupById("failed-1").orElseThrow();

        assertEquals(FAILED, record.status());
        assertNull(record.filePath());
        assertEquals("radarr: boom", record.errorMessage());
        assertEquals(1, record.fetchedServices().size());
    }

    @Test
    void fetchesLatestBackupRecordByStartTime() {
        db.backups.addNewBackupRecord(backupRun("older", "2026-01-01 00:00:00"));
        db.backups.addNewBackupRecord(backupRun("newer", "2026-02-01 00:00:00"));

        assertEquals("newer", db.backups.fetchLatestBackupRecord().orElseThrow().archiveId());
    }

    @Test
    void returnsEmptyForMissingBackupRecords() {
        assertTrue(db.backups.fetchBackupById("missing").isEmpty());
        assertTrue(db.backups.fetchLatestBackupRecord().isEmpty());
    }

    @Test
    void deletingBackupRecordCascadesToServiceRows() throws Exception {
        db.backups.addNewBackupRecord(backupRun("run-1", "2026-01-01 00:00:00", serviceResult("radarr", COMPLETED, null)));
        assertEquals(1, count("backup_services"));

        db.backups.deleteBackupRecordById("run-1");

        assertEquals(0, count("backup_services"));
        assertTrue(db.backups.fetchBackupById("run-1").isEmpty());
    }

    @Test
    void lastSuccessfulStartTimeIgnoresFailedRunsAndCountsPartialOnes() {
        assertTrue(db.backups.fetchLastSuccessfulStartTime().isEmpty());

        db.backups.addNewBackupRecord(backupRun("good", "2026-01-01 00:00:00"));
        db.backups.addNewBackupRecord(new BackupRunResult("failed-1", "2026-03-01 00:00:00", "2026-03-01 00:00:01", FAILED,
            List.of(), null, "boom"));
        assertEquals(java.time.LocalDateTime.of(2026, 1, 1, 0, 0).atZone(java.time.ZoneId.systemDefault()).toInstant(),
            db.backups.fetchLastSuccessfulStartTime().orElseThrow());

        db.backups.addNewBackupRecord(new BackupRunResult("partial-1", "2026-02-01 00:00:00", "2026-02-01 00:00:01", PARTIAL,
            List.of(), null, null));
        assertEquals(java.time.LocalDateTime.of(2026, 2, 1, 0, 0).atZone(java.time.ZoneId.systemDefault()).toInstant(),
            db.backups.fetchLastSuccessfulStartTime().orElseThrow());
    }
}
