package wellatleastitried.mediagarrdServer.service;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

final class ZipPartIndex {

    private static final int CACHE_KIB = 2048;

    private ZipPartIndex() {}

    static Connection open(Path file) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=OFF");
            statement.execute("PRAGMA synchronous=OFF");
            statement.execute("PRAGMA cache_size=-" + CACHE_KIB);
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
