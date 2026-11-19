package wellatleastitried.mediagarrdServer.repository;

import static wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.formatTime;
import static wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.parseTime;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.ADD_BACKUP_RECORD;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.ADD_BACKUP_SERVICE_RECORD;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.DELETE_BACKUP_RECORD_BY_ID;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_LAST_SUCCESSFUL_START;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_LATEST_RECORD;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_RECORD_BY_ID;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_SERVICES_FROM_RECORD;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.model.FetchedBackupRecord;
import wellatleastitried.mediagarrdServer.model.FetchedBackupServiceRecord;

@Repository
public class BackupRecordRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupRecordRepository.class);

    private final SqliteDatabase database;

    public BackupRecordRepository(SqliteDatabase database) {
        this.database = database;
    }

    public void addNewBackupRecord(BackupRunResult backupRecord) {
        database.readLock().lock();
        try (var connection = database.openVerifiedConnection()) {
            connection.setAutoCommit(false);
            Integer backupId = addMasterBackup(connection, backupRecord);
            if (backupId != null) {
                addServiceBackups(connection, backupId, backupRecord.serviceResults());
            } else if (backupRecord.archive() != null) {
                LOGGER.warn("Could not record backup {}; this archive will not be pruned by retention", backupRecord.runId());
            }
        } catch (SQLException e) {
            LOGGER.warn("Could not log record of backup: " + backupRecord.runId(), e);
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while attempting to open a connection to the database", e);
        } finally {
            database.readLock().unlock();
        }
    }

    public Optional<FetchedBackupRecord> fetchBackupById(String archiveId) {
        FetchedBackupRecord backupRecord = null;
        database.readLock().lock();
        try (var connection = database.openVerifiedConnection()) {
            backupRecord = fetchMasterRecordById(connection, archiveId);
            if (backupRecord != null) {
                backupRecord = populateFetchedRecordWithServices(connection, backupRecord);
            }
        } catch (SQLException e) {
            LOGGER.warn("An error occurred while fetching a backup record by archive_id (" + archiveId + ")", e);
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while fetching a backup record by archive_id (" + archiveId + ")", e);
        } finally {
            database.readLock().unlock();
        }

        return Optional.ofNullable(backupRecord);
    }

    public Optional<FetchedBackupRecord> fetchLatestBackupRecord() {
        FetchedBackupRecord backupRecord = null;
        database.readLock().lock();
        try (var connection = database.openVerifiedConnection()) {
            backupRecord = fetchLatestMasterRecord(connection);
            if (backupRecord != null) {
                backupRecord = populateFetchedRecordWithServices(connection, backupRecord);
            }
        } catch (SQLException e) {
            LOGGER.warn("An error occurred while fetching the latest backup record", e);
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while fetching the latest backup record", e);
        } finally {
            database.readLock().unlock();
        }

        return Optional.ofNullable(backupRecord);
    }

    public Optional<Instant> fetchLastSuccessfulStartTime() {
        return database.query("fetch the last successful backup", FETCH_LAST_SUCCESSFUL_START, rs -> rs.getString("start_time"))
            .stream()
            .findFirst()
            .map(BackupRecordRepository::parseStartTime);
    }

    private static Instant parseStartTime(String startTime) {
        try {
            return parseTime(startTime);
        } catch (ParseException | NullPointerException e) {
            throw new IllegalStateException("Unreadable backup start time: " + startTime, e);
        }
    }

    public void deleteBackupRecordById(String archiveId) {
        database.readLock().lock();
        try (var connection = database.openVerifiedConnection();
            var statement = connection.prepareStatement(DELETE_BACKUP_RECORD_BY_ID)) {
            statement.setString(1, archiveId);
            statement.executeUpdate();
        } catch (SQLException e) {
            database.invalidateIfDamaged(e);
            LOGGER.warn("Error deleting a backup record with archive_id (" + archiveId + ")", e);
        } finally {
            database.readLock().unlock();
        }
    }

    private Integer addMasterBackup(Connection connection, BackupRunResult backupRecord) {
        try {
            return database.inTransaction(connection, transaction -> insertMasterBackup(transaction, backupRecord));
        } catch (SQLException e) {
            LOGGER.warn("An error occurred while logging a new backup record", e);
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while logging a new backup record", e);
        }
        return null;
    }

    private static int insertMasterBackup(Connection connection, BackupRunResult backupRecord) throws SQLException {
        try (var statement = connection.prepareStatement(ADD_BACKUP_RECORD)) {
            statement.setString(1, backupRecord.runId());
            statement.setString(2, backupRecord.startTime());
            statement.setString(3, backupRecord.endTime());
            statement.setString(4, backupRecord.status());
            BackupArchive archive = backupRecord.archive();
            statement.setString(5, archive == null ? null : archive.resolvedChecksum());
            statement.setString(6, archive == null ? null : archive.path().toString());
            statement.setLong(7, archive == null ? 0 : archive.sizeBytes());
            statement.setString(8, archive == null ? null : formatTime(archive.createdAt()));
            statement.setString(9, backupRecord.errorMessage());
            statement.executeUpdate();
        }
        try (var idQuery = connection.createStatement();
            var keys = idQuery.executeQuery("SELECT last_insert_rowid()")) {
            return keys.getInt(1);
        }
    }

    private void addServiceBackups(Connection connection, int backupId, List<BackupServiceResult> serviceResults) {
        for (BackupServiceResult service : serviceResults) {
            try {
                database.inTransaction(connection, transaction -> insertServiceBackup(transaction, backupId, service));
            } catch (SQLException e) {
                LOGGER.warn("An error occurred while adding a new backup service record for " + service.serviceName(), e);
            }
        }
    }

    private static Void insertServiceBackup(Connection connection, int backupId, BackupServiceResult service) throws SQLException {
        try (var statement = connection.prepareStatement(ADD_BACKUP_SERVICE_RECORD)) {
            statement.setInt(1, backupId);
            statement.setString(2, service.serviceName());
            statement.setString(3, service.status());
            statement.setString(4, service.startTime());
            statement.setString(5, service.endTime());
            statement.setString(6, service.errorMessage());
            statement.executeUpdate();
        }
        return null;
    }

    private FetchedBackupRecord fetchMasterRecordById(Connection connection, String archiveId) {
        try (var statement = connection.prepareStatement(FETCH_RECORD_BY_ID)) {
            statement.setString(1, archiveId);
            var rs = statement.executeQuery();
            return buildFetchedBackupRecordFromQueryResult(rs);
        } catch (SQLException e) {
            database.invalidateIfDamaged(e);
            LOGGER.warn("Error fetching backup record by archive_id " + archiveId + ")", e);
            return null;
        }
    }

    private FetchedBackupRecord fetchLatestMasterRecord(Connection connection) {
        try (var statement = connection.createStatement()) {
            var rs = statement.executeQuery(FETCH_LATEST_RECORD);
            return buildFetchedBackupRecordFromQueryResult(rs);
        } catch (SQLException e) {
            database.invalidateIfDamaged(e);
            LOGGER.warn("Error fetching latest backup record", e);
            return null;
        }
    }

    private FetchedBackupRecord buildFetchedBackupRecordFromQueryResult(ResultSet rs) throws SQLException {
        if (!rs.next()) {
            return null;
        }

        int id = rs.getInt("id");
        String archiveId = rs.getString("archive_id");
        String backupStartTime = rs.getString("start_time");
        String backupEndTime = rs.getString("end_time");
        String status = rs.getString("status");
        String filePath = rs.getString("file_path");
        String errorMessage = rs.getString("error_message");

        return new FetchedBackupRecord(id, archiveId, backupStartTime, backupEndTime, status, filePath, new ArrayList<FetchedBackupServiceRecord>(), errorMessage);
    }

    private FetchedBackupRecord populateFetchedRecordWithServices(Connection connection, FetchedBackupRecord backupRecord) {
        try (var statement = connection.prepareStatement(FETCH_SERVICES_FROM_RECORD)) {
            statement.setInt(1, backupRecord.id());
            var rs = statement.executeQuery();

            while (rs.next()) {
                String serviceName = rs.getString("service_name");
                String status = rs.getString("status");
                String startTime = rs.getString("start_time");
                String endTime = rs.getString("end_time");
                String errorMessage = rs.getString("error_message");
                var serviceRecord = new FetchedBackupServiceRecord(
                    serviceName,
                    status,
                    startTime,
                    endTime,
                    errorMessage
                );
                backupRecord.fetchedServices().add(serviceRecord);
            }
            return backupRecord;
        } catch (SQLException e) {
            database.invalidateIfDamaged(e);
            LOGGER.warn("Error populating backup record with related services", e);
            return null;
        }
    }
}
