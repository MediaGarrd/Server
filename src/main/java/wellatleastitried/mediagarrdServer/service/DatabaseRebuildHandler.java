package wellatleastitried.mediagarrdServer.service;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import wellatleastitried.mediagarrdServer.repository.SqliteDatabase;

@Component
@Lazy(false)
public class DatabaseRebuildHandler {

    private final PairingService pairing;
    private final RunStatusBroadcaster broadcaster;
    private final BackupArchiveService archives;
    private final BackupScheduleService schedule;

    public DatabaseRebuildHandler(SqliteDatabase database, PairingService pairing, RunStatusBroadcaster broadcaster,
                                  BackupArchiveService archives, BackupScheduleService schedule) {
        this.pairing = pairing;
        this.broadcaster = broadcaster;
        this.archives = archives;
        this.schedule = schedule;
        database.addRebuildListener(this::reset);
    }

    void reset() {
        pairing.resetAfterRebuild();
        archives.reloadFromSettings();
        schedule.reloadFromSettings();
        broadcaster.closeAll();
    }
}
