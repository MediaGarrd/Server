package wellatleastitried.mediagarrdServer.repository;

import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_SETTING;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.UPSERT_SETTING;

import java.util.Optional;

import org.springframework.stereotype.Repository;

@Repository
public class SettingsRepository {

    private final SqliteDatabase database;

    public SettingsRepository(SqliteDatabase database) {
        this.database = database;
    }

    public Optional<String> fetchSetting(String key) {
        return database.query("fetch setting " + key, FETCH_SETTING, rs -> rs.getString("value"), key).stream().findFirst();
    }

    public void saveSetting(String key, String value) {
        database.update("save setting " + key, UPSERT_SETTING, key, value);
    }
}
