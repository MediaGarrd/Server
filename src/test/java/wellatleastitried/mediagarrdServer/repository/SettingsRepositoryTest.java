package wellatleastitried.mediagarrdServer.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class SettingsRepositoryTest extends DatabaseTestBase {

    @Test
    void savesAndOverwritesSettings() {
        assertTrue(db.settings.fetchSetting("backup_root").isEmpty());

        db.settings.saveSetting("backup_root", "/first");
        db.settings.saveSetting("backup_root", "/second");

        assertEquals(Optional.of("/second"), db.settings.fetchSetting("backup_root"));
    }
}
