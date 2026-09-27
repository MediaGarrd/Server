package wellatleastitried.mediagarrdServer.utilities.database;

public class DatabaseUtils {

    public static final String SQLITE = "jdbc:sqlite:";
    public static final String databasePath = "/var/lib/mediagarrd/mediagarrd.db";

    // Status used for the overall backup AND the individual service runners
    public class Status {
        public static final String COMPLETED = "completed";
        public static final String PARTIAL = "partial";
        public static final String FAILED = "failed";
    }

}
