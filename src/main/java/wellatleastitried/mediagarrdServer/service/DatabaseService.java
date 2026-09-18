package wellatleastitried.mediagarrdServer.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import wellatleastitried.mediagarrdServer.MediaGarrdUtilities.Utils;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.model.FetchedBackupRecord;
import wellatleastitried.mediagarrdServer.model.FetchedBackupServiceRecord;

import static wellatleastitried.mediagarrd.MediaGarrdUtilities.DatabaseConstants.*;

@Service
public class DatabaseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseService.class);

    public DatabaseService() {
        verifyDatabaseIntegrity();
        try (var connection = DriverManager.getConnection(SQLITE + databaseUrl)) {
            connection.setAutoCommit(false);
        } catch (Exception e) {
            LOGGER.warn("An error occurred while disabling auto-commit on the database", e);
        }
    }

    private boolean verifyDatabaseIntegrity() {
        try (var connection = DriverManager.getConnection(SQLITE + databaseUrl)) {
            connection.setAutoCommit(false);
            ensureTablesExist(connection);
            runNeededMigrations(connection);
        } catch  (SQLException sE) {
            LOGGER.warn("An error occurred while verifying the integrity of the database", sE);
            return false;
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while verifying the integrity of the database", e);
            return false;
        }

        return true;
    }

    private void ensureTablesExist(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(CREATE_TABLES);
            connection.commit();
        } catch (SQLException sE) {
            LOGGER.warn("Failed to verify if tables exist in database.", sE);
            handleSqlException(connection);
            throw sE;
        }
    }

    private void runNeededMigrations(Connection connection) throws SQLException {
        int currentVersion = getDatabaseVersion(connection);
        if (currentVersion == -1) return;

        // 0 means it is a brand new database, or it was created before migrations existed
        if (currentVersion == 0) {
            // Initial tables will be "v1"
            currentVersion = 1;
            setDatabaseVersion(connection, currentVersion);
        }

        for (Migration migration : MIGRATIONS) {
            if (currentVersion <= migration.version()) {
                continue;
            }

            try (var statement = connection.createStatement()) {
                statement.execute(migration.sql());
            } catch (SQLException sE) {
                LOGGER.warn(
                    "Migration failed for v" + migration.version() + ". Aborting the rest of the migrations...",
                    sE
                );
                handleSqlException(connection);
                throw sE;
            }

            currentVersion = migration.version();
            setDatabaseVersion(connection, currentVersion);
        }

        connection.commit();
    }

    private int getDatabaseVersion(Connection connection) {
        try (var statement = connection.createStatement();
        var result = statement.executeQuery("PRAGMA user_version")) {

            return result.getInt(1);
        } catch (SQLException sE) {
            LOGGER.warn("Failed to fetch database version", sE);
            return -1;
        }
    }

    private void setDatabaseVersion(Connection connection, int version) {

        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + version);
        } catch (SQLException sE) {
            LOGGER.warn("Failed to set new database version", sE);
        }
    }

    public void addNewBackupRecord(BackupRunResult backupRecord) {
        if (!verifyDatabaseIntegrity()) {
            LOGGER.warn("Could not log record of backup: " + backupRecord.runId());
            return;
        }

        try (var connection = DriverManager.getConnection(SQLITE + databaseUrl)) {
            connection.setAutoCommit(false);
            addMasterBackup(connection, backupRecord);
            addServiceBackups(connection, backupRecord.serviceResults());
        } catch (SQLException sE) {
            LOGGER.warn("An error occurred while attempting to open a connection to the database", sE);
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while attempting to open a connection to the database", e);
        }
    }

    private void addMasterBackup(Connection connection, BackupRunResult backupRecord) {
        try (var statement = connection.prepareStatement(ADD_BACKUP_RECORD)) {
            statement.setString(1, backupRecord.runId());
            statement.setString(2, backupRecord.startTime());
            statement.setString(3, backupRecord.endTime());
            statement.setString(4, backupRecord.status());
            statement.setString(5, backupRecord.archive().generateChecksum());
            statement.setString(6, backupRecord.archive().fileName());
            statement.setInt(7, backupRecord.archive().fileSize());
            statement.setString(8, Utils.formatTime(backupRecord.archive().createdAt()));
            statement.setString(9, backupRecord.errorMessage());
            statement.executeUpdate();
            connection.commit();
        } catch  (SQLException sE) {
            LOGGER.warn("An error occurred while logging a new backup record", sE);
            handleSqlException(connection);
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while logging a new backup record", e);
        }
    }

    private void addServiceBackups(Connection connection, List<BackupServiceResult> serviceResults) {
        for (BackupServiceResult service : serviceResults) {
            try (var statement = connection.prepareStatement(ADD_BACKUP_SERVICE_RECORD)) {
                statement.setString(1, service.runId());
                statement.setString(2, service.serviceName());
                statement.setString(3, service.status());
                statement.setString(4, service.startTime());
                statement.setString(5, service.endTime());
                statement.setString(6, service.errorMessage());
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException sE) {
                LOGGER.warn("An error occurred while adding a new backup service record for " + service.serviceName(), sE);
                handleSqlException(connection);
            }
        }
    }

    public FetchedBackupRecord fetchLatestBackupRecord() {
        if (!verifyDatabaseIntegrity()) {
            LOGGER.warn("Could not fetch latest backup record");
            return null;
        }

        try (var connection = DriverManager.getConnection(SQLITE + databaseUrl)) {
            FetchedBackupRecord backupRecord = fetchLatestMasterRecord(connection);
            backupRecord = populateFetchedRecordWithServices(connection, backupRecord);
            return backupRecord;
        } catch  (SQLException sE) {
            LOGGER.warn("An error occurred while fetching the latest backup record", sE);
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while fetching the latest backup record", e);
        }

        return null;
    }

    private FetchedBackupRecord fetchLatestMasterRecord(Connection connection) {
        try (var statement = connection.createStatement()) {
            var rs = statement.executeQuery(FETCH_LATEST_RECORD);
            int id = rs.getInt("id");
            String backupStartTime = rs.getString("start_time");
            String status = rs.getString("status");
            String filename = rs.getString("filename");
            return new FetchedBackupRecord(id, backupStartTime, status, filename, new ArrayList<FetchedBackupServiceRecord>());
        } catch (SQLException sE) {
            LOGGER.warn("Error fetching latest backup record", sE);
            return null;
        }
    }

    private FetchedBackupRecord populateFetchedRecordWithServices(Connection connection, FetchedBackupRecord backupRecord) {
        try (var statement = connection.prepareStatement(FETCH_SERVICES_FROM_LATEST_RECORD)) {
            statement.setInt(1, backupRecord.id());
            var rs = statement.executeQuery();

            while (rs.next()) {
                String serviceName = rs.getString("service_name");
                String status = rs.getString("status");
                String startTime = rs.getString("start_time");
                var serviceRecord = new FetchedBackupServiceRecord(
                    serviceName,
                    status,
                    startTime
                );
                backupRecord.fetchedServices().add(serviceRecord);
            }
            return backupRecord;
        } catch (SQLException sE) {
            LOGGER.warn("Error fetching latest backup record", sE);
            return null;
        }
    }

    private void handleSqlException(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            LOGGER.warn("Failed to rollback update after previous SQL failure, there is an issue with the database!");
        }
    }
}
