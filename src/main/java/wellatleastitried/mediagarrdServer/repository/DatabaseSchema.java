package wellatleastitried.mediagarrdServer.repository;

import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.CREATE_TABLES;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.MIGRATIONS;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.Migration;

final class DatabaseSchema {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseSchema.class);

    private static final List<String> REQUIRED_TABLES = List.of("backups", "backup_services", "services", "settings");

    record Fingerprint(int userVersion, int schemaVersion) {}

    private DatabaseSchema() {}

    static Fingerprint fingerprint(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
            var rs = statement.executeQuery("SELECT user_version, schema_version FROM pragma_user_version, pragma_schema_version")) {
            return new Fingerprint(rs.getInt(1), rs.getInt(2));
        }
    }

    static void ensureTablesExist(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            for (String sql : CREATE_TABLES.split(";")) {
                String createStatement = sql.trim();
                if (!createStatement.isEmpty()) {
                    statement.execute(createStatement);
                }
            }

            connection.commit();
        } catch (SQLException e) {
            LOGGER.warn("Failed to verify if tables exist in database.", e);
            rollback(connection);
            throw e;
        }
    }

    static void runNeededMigrations(Connection connection) throws SQLException {
        int currentVersion = databaseVersion(connection);
        if (currentVersion == -1) return;

        // 0 means it is a brand new database, or it was created before migrations existed
        if (currentVersion == 0) {
            // Initial tables will be "v1"
            currentVersion = 1;
            setDatabaseVersion(connection, currentVersion);
        }

        for (Migration migration : MIGRATIONS) {
            if (currentVersion >= migration.version()) {
                continue;
            }

            try (var statement = connection.createStatement()) {
                statement.execute(migration.sql());
            } catch (SQLException e) {
                LOGGER.warn(
                    "Migration failed for v" + migration.version() + ". Aborting the rest of the migrations...",
                    e
                );
                rollback(connection);
                throw e;
            }

            currentVersion = migration.version();
            setDatabaseVersion(connection, currentVersion);
        }

        connection.commit();
    }

    static void requireCompleteSchema(Connection connection) throws SQLException {
        if (databaseVersion(connection) < 1) {
            throw new SQLException("Snapshot is missing its schema version");
        }
        List<String> tables = new ArrayList<>();
        try (var statement = connection.createStatement();
            var rs = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        if (!tables.containsAll(REQUIRED_TABLES)) {
            throw new SQLException("Snapshot is missing tables, found " + tables);
        }
    }

    static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            LOGGER.warn("Failed to rollback update after previous SQL failure, there is an issue with the database!");
        }
    }

    private static int databaseVersion(Connection connection) {
        try (var statement = connection.createStatement();
            var result = statement.executeQuery("PRAGMA user_version")) {
            return result.getInt(1);
        } catch (SQLException e) {
            LOGGER.warn("Failed to fetch database version", e);
            return -1;
        }
    }

    private static void setDatabaseVersion(Connection connection, int version) {
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + version);
        } catch (SQLException e) {
            LOGGER.warn("Failed to set new database version", e);
        }
    }
}
