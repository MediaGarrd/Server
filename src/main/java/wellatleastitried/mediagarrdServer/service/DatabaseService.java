package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.formatTime;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.ADD_BACKUP_RECORD;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.ADD_BACKUP_SERVICE_RECORD;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.ADD_SERVICE;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.CREATE_TABLES;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.DELETE_BACKUP_RECORD_BY_ID;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.DELETE_SERVICE;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_ALL_SERVICES;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_LATEST_RECORD;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_RECORD_BY_ID;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_SERVICES_FROM_RECORD;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_SERVICE_BY_NAME;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_SETTING;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.MIGRATIONS;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.UPDATE_SERVICE;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.UPSERT_SETTING;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.SQLITE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.model.FetchedBackupRecord;
import wellatleastitried.mediagarrdServer.model.FetchedBackupServiceRecord;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;
import wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.Migration;

@Service
public class DatabaseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> PATH_LIST = new TypeReference<>() {};

    private static final DateTimeFormatter QUARANTINE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneOffset.UTC);
    private static final List<String> DATABASE_FILE_SUFFIXES = List.of("", "-journal", "-wal", "-shm");

    private final Path databaseFile;
    private final String databaseUrl;
    private volatile SchemaFingerprint verifiedSchema;

    private record SchemaFingerprint(int userVersion, int schemaVersion) {}

    public DatabaseService(MediaGarrdProperties properties) {
        databaseFile = Path.of(properties.getDatabasePath()).toAbsolutePath();
        databaseUrl = SQLITE + databaseFile;
        verifyDatabaseIntegrity();
    }

    protected Connection getConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(databaseUrl);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException sE) {
            connection.close();
            throw sE;
        }
        return connection;
    }

    protected synchronized boolean verifyDatabaseIntegrity() {
        verifiedSchema = null;
        try {
            rebuildIfNeeded();
            return true;
        } catch (SQLException sE) {
            if (!isCorrupt(sE) || !quarantineDatabase()) {
                LOGGER.warn("An error occurred while verifying the integrity of the database", sE);
                return false;
            }
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while verifying the integrity of the database", e);
            return false;
        }

        try {
            rebuildIfNeeded();
            return true;
        } catch (Exception e) {
            LOGGER.warn("Unable to create a new database after moving the corrupt one aside", e);
            return false;
        }
    }

    private void rebuildIfNeeded() throws SQLException {
        try {
            Files.createDirectories(databaseFile.getParent());
        } catch (IOException iE) {
            LOGGER.warn("Unable to create database directory " + databaseFile.getParent(), iE);
        }
        try (var connection = getConnection()) {
            checkNotCorrupt(connection);
            connection.setAutoCommit(false);
            ensureTablesExist(connection);
            runNeededMigrations(connection);
            verifiedSchema = fingerprint(connection);
        }
    }

    private Connection openVerifiedConnection() throws SQLException {
        SchemaFingerprint expected = verifiedSchema;
        if (expected != null) {
            Connection connection = null;
            try {
                connection = getConnection();
                if (fingerprint(connection).equals(expected)) {
                    return connection;
                }
            } catch (SQLException sE) {
                LOGGER.debug("Unable to read the database schema fingerprint", sE);
            }
            if (connection != null) {
                connection.close();
            }
            LOGGER.info("Database does not match the verified schema, rebuilding it");
        }
        if (!verifyDatabaseIntegrity()) {
            throw new SQLException("Database integrity check failed");
        }
        return getConnection();
    }

    private static SchemaFingerprint fingerprint(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
            var rs = statement.executeQuery("SELECT user_version, schema_version FROM pragma_user_version, pragma_schema_version")) {
            return new SchemaFingerprint(rs.getInt(1), rs.getInt(2));
        }
    }

    private static void checkNotCorrupt(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery("PRAGMA quick_check")) {
            String result = rs.next() ? rs.getString(1) : "no result";
            if (!"ok".equals(result)) {
                throw new CorruptDatabaseException("Database failed its integrity check: " + result);
            }
        }
    }

    private boolean invalidateIfDamaged(SQLException e) {
        String message = String.valueOf(e.getMessage());
        boolean damaged = isCorrupt(e) || message.contains("no such table") || message.contains("no such column");
        if (damaged) {
            verifiedSchema = null;
        }
        return damaged;
    }

    private static boolean isCorrupt(SQLException e) {
        if (e instanceof CorruptDatabaseException) {
            return true;
        }
        if (e instanceof SQLiteException sqlite) {
            int code = sqlite.getResultCode().code & 0xFF;
            return code == SQLiteErrorCode.SQLITE_CORRUPT.code || code == SQLiteErrorCode.SQLITE_NOTADB.code;
        }
        return false;
    }

    private boolean quarantineDatabase() {
        if (!Files.isRegularFile(databaseFile)) {
            return false;
        }
        String suffix = ".corrupt-" + QUARANTINE_FORMAT.format(Instant.now());
        try {
            for (String fileSuffix : DATABASE_FILE_SUFFIXES) {
                Path file = Path.of(databaseFile + fileSuffix);
                if (Files.isRegularFile(file)) {
                    Files.move(file, Path.of(file + suffix));
                }
            }
        } catch (IOException iE) {
            LOGGER.error("Database {} is corrupt and could not be moved aside", databaseFile, iE);
            return false;
        }
        LOGGER.error("Database {} was corrupt, moved it to {}{} and created a new one. Settings and services must be configured again",
            databaseFile, databaseFile, suffix);
        return true;
    }

    private static class CorruptDatabaseException extends SQLException {
        CorruptDatabaseException(String message) {
            super(message);
        }
    }

    private void ensureTablesExist(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            for (String sql : CREATE_TABLES.split(";")) {
                String CREATE_STATEMENT = sql.trim();
                if (!CREATE_STATEMENT.isEmpty()) {
                    statement.execute(CREATE_STATEMENT);
                }
            }

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
            if (currentVersion >= migration.version()) {
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

    protected int getDatabaseVersion(Connection connection) {
        try (var statement = connection.createStatement();
        var result = statement.executeQuery("PRAGMA user_version")) {

            return result.getInt(1);
        } catch (SQLException sE) {
            LOGGER.warn("Failed to fetch database version", sE);
            return -1;
        }
    }

    protected void setDatabaseVersion(Connection connection, int version) {
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + version);
        } catch (SQLException sE) {
            LOGGER.warn("Failed to set new database version", sE);
        }
    }

    public void addNewBackupRecord(BackupRunResult backupRecord) {
        try (var connection = openVerifiedConnection()) {
            connection.setAutoCommit(false);
            Integer backupId = addMasterBackup(connection, backupRecord);
            if (backupId != null) {
                addServiceBackups(connection, backupId, backupRecord.serviceResults());
            }
        } catch (SQLException sE) {
            LOGGER.warn("Could not log record of backup: " + backupRecord.runId(), sE);
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while attempting to open a connection to the database", e);
        }
    }

    private Integer addMasterBackup(Connection connection, BackupRunResult backupRecord) {
        try (var statement = connection.prepareStatement(ADD_BACKUP_RECORD)) {
            statement.setString(1, backupRecord.runId());
            statement.setString(2, backupRecord.startTime());
            statement.setString(3, backupRecord.endTime());
            statement.setString(4, backupRecord.status());
            BackupArchive archive = backupRecord.archive();
            statement.setString(5, archive == null ? null : archive.generateChecksum());
            statement.setString(6, archive == null ? null : archive.path().toString());
            statement.setLong(7, archive == null ? 0 : archive.sizeBytes());
            statement.setString(8, archive == null ? null : formatTime(archive.createdAt()));
            statement.setString(9, backupRecord.errorMessage());
            statement.executeUpdate();
            int backupId;
            try (var idQuery = connection.createStatement();
                var keys = idQuery.executeQuery("SELECT last_insert_rowid()")) {
                backupId = keys.getInt(1);
            }
            connection.commit();
            return backupId;
        } catch  (SQLException sE) {
            invalidateIfDamaged(sE);
            LOGGER.warn("An error occurred while logging a new backup record", sE);
            handleSqlException(connection);
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while logging a new backup record", e);
        }
        return null;
    }

    private void addServiceBackups(Connection connection, int backupId, List<BackupServiceResult> serviceResults) {
        for (BackupServiceResult service : serviceResults) {
            try (var statement = connection.prepareStatement(ADD_BACKUP_SERVICE_RECORD)) {
                statement.setInt(1, backupId);
                statement.setString(2, service.serviceName());
                statement.setString(3, service.status());
                statement.setString(4, service.startTime());
                statement.setString(5, service.endTime());
                statement.setString(6, service.errorMessage());
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException sE) {
                invalidateIfDamaged(sE);
                LOGGER.warn("An error occurred while adding a new backup service record for " + service.serviceName(), sE);
                handleSqlException(connection);
            }
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

    public Optional<FetchedBackupRecord> fetchBackupById(String archiveId) {
        FetchedBackupRecord backupRecord = null;
        try (var connection = openVerifiedConnection()) {
            backupRecord = fetchMasterRecordById(connection, archiveId);
            if (backupRecord != null) {
                backupRecord = populateFetchedRecordWithServices(connection, backupRecord);
            }
        } catch  (SQLException sE) {
            LOGGER.warn("An error occurred while fetching a backup record by archive_id (" + archiveId + ")", sE);
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while fetching a backup record by archive_id (" + archiveId + ")", e);
        }

        return Optional.ofNullable(backupRecord);
    }

    private FetchedBackupRecord fetchMasterRecordById(Connection connection, String archiveId) {
        try (var statement = connection.prepareStatement(FETCH_RECORD_BY_ID)) {
            statement.setString(1, archiveId);
            var rs = statement.executeQuery();
            return buildFetchedBackupRecordFromQueryResult(rs);
        } catch (SQLException sE) {
            invalidateIfDamaged(sE);
            LOGGER.warn("Error fetching backup record by archive_id " + archiveId + ")", sE);
            return null;
        }
    }

    public Optional<FetchedBackupRecord> fetchLatestBackupRecord() {
        FetchedBackupRecord backupRecord = null;
        try (var connection = openVerifiedConnection()) {
            backupRecord = fetchLatestMasterRecord(connection);
            if (backupRecord != null) {
                backupRecord = populateFetchedRecordWithServices(connection, backupRecord);
            }
        } catch  (SQLException sE) {
            LOGGER.warn("An error occurred while fetching the latest backup record", sE);
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while fetching the latest backup record", e);
        }

        return Optional.ofNullable(backupRecord);
    }

    private FetchedBackupRecord fetchLatestMasterRecord(Connection connection) {
        try (var statement = connection.createStatement()) {
            var rs = statement.executeQuery(FETCH_LATEST_RECORD);
            return buildFetchedBackupRecordFromQueryResult(rs);
        } catch (SQLException sE) {
            invalidateIfDamaged(sE);
            LOGGER.warn("Error fetching latest backup record", sE);
            return null;
        }
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
        } catch (SQLException sE) {
            invalidateIfDamaged(sE);
            LOGGER.warn("Error populating backup record with related services", sE);
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

    public void deleteBackupRecordById(String archiveId) {
        try (var connection = openVerifiedConnection();
            var statement = connection.prepareStatement(DELETE_BACKUP_RECORD_BY_ID)) {
            statement.setString(1, archiveId);
            statement.executeUpdate();
        } catch (SQLException sE) {
            invalidateIfDamaged(sE);
            LOGGER.warn("Error deleting a backup record with archive_id (" + archiveId + ")", sE);
        }
    }

    public List<ServiceDefinition> fetchServices() {
        return query("fetch configured services", FETCH_ALL_SERVICES, DatabaseService::toServiceDefinition);
    }

    public Optional<ServiceDefinition> fetchService(String name) {
        return query("fetch service " + name, FETCH_SERVICE_BY_NAME, DatabaseService::toServiceDefinition, name)
            .stream().findFirst();
    }

    public void addService(ServiceDefinition service) {
        update("add service " + service.name(), ADD_SERVICE, service.name(), toJson(service.paths()));
    }

    public boolean updateService(String name, ServiceDefinition service) {
        return update("update service " + name, UPDATE_SERVICE, service.name(), toJson(service.paths()), name) > 0;
    }

    public boolean removeService(String name) {
        return update("remove service " + name, DELETE_SERVICE, name) > 0;
    }

    public Optional<String> fetchSetting(String key) {
        return query("fetch setting " + key, FETCH_SETTING, rs -> rs.getString("value"), key).stream().findFirst();
    }

    public void saveSetting(String key, String value) {
        update("save setting " + key, UPSERT_SETTING, key, value);
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    private interface StatementWork<T> {
        T run(PreparedStatement statement) throws SQLException;
    }

    private <T> List<T> query(String action, String sql, RowMapper<T> mapper, String... params) {
        return execute(action, sql, params, statement -> {
            try (var rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        });
    }

    private int update(String action, String sql, String... params) {
        return execute(action, sql, params, PreparedStatement::executeUpdate);
    }

    private <T> T execute(String action, String sql, String[] params, StatementWork<T> work) {
        for (int attempt = 1; ; attempt++) {
            try (var connection = openVerifiedConnection(); var statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                return work.run(statement);
            } catch (SQLException sE) {
                if (attempt > 1 || !invalidateIfDamaged(sE)) {
                    throw new IllegalStateException("Failed to " + action, sE);
                }
                LOGGER.warn("Database is damaged or out of date, rebuilding and retrying: {}", sE.getMessage());
            }
        }
    }

    private static void bind(PreparedStatement statement, String... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setString(i + 1, params[i]);
        }
    }

    private static ServiceDefinition toServiceDefinition(ResultSet rs) throws SQLException {
        try {
            return new ServiceDefinition(rs.getString("name"), JSON.readValue(rs.getString("paths"), PATH_LIST));
        } catch (JsonProcessingException jE) {
            throw new SQLException("Corrupt paths for service " + rs.getString("name"), jE);
        }
    }

    private static String toJson(List<String> paths) {
        try {
            return JSON.writeValueAsString(paths);
        } catch (JsonProcessingException jE) {
            throw new IllegalStateException("Failed to serialize service paths", jE);
        }
    }
}
