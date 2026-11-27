package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

class SqliteVersionTest {

    @Test
    void bundledSqliteIsNotAffectedByCve20256965() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select sqlite_version()")) {
            assertTrue(rs.next());
            String[] parts = rs.getString(1).split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            assertTrue(major > 3 || (major == 3 && (minor > 50 || (minor == 50 && patch >= 2))),
                "SQLite 3.50.2 or later is required but was " + rs.getString(1));
        }
    }
}
