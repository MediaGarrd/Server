package wellatleastitried.mediagarrdServer.utilities.database;

public class DatabaseUtils {

    public static final String SQLITE = "jdbc:sqlite:";
    public static final String BACKUP_ROOT_SETTING = "backup_root";
    public static final String SETUP_COMPLETED_SETTING = "setup_completed";
    public static final String BACKUP_INTERVAL_SETTING = "backup_interval";
    public static final String RETENTION_COUNT_SETTING = "retention_count";

    // Status used for the overall backup AND the individual service runners
    public class Status {
        public static final String COMPLETED = "completed";
        public static final String PARTIAL = "partial";
        public static final String FAILED = "failed";
    }

}
