package wellatleastitried.mediagarrdServer.repository;

import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.SQLITE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.utilities.FilePermissions;

@Component
public class SqliteDatabase {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteDatabase.class);

    private final ReentrantReadWriteLock databaseLock = new ReentrantReadWriteLock();
    private final Path databaseFile;
    private final String databaseUrl;
    private final List<DatabaseRebuildListener> rebuildListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService rebuildNotifier = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "database-rebuild-notifier");
        thread.setDaemon(true);
        return thread;
    });
    private volatile DatabaseSchema.Fingerprint verifiedSchema;

    public SqliteDatabase(MediaGarrdProperties properties) {
        databaseFile = Path.of(properties.getDatabasePath()).toAbsolutePath();
        databaseUrl = SQLITE + databaseFile;
        verifyDatabaseIntegrity();
    }

    @PreDestroy
    public void shutdownRebuildNotifier() {
        rebuildNotifier.shutdown();
        try {
            if (!rebuildNotifier.awaitTermination(5, TimeUnit.SECONDS)) {
                rebuildNotifier.shutdownNow();
                rebuildNotifier.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            rebuildNotifier.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    interface TransactionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface StatementWork<T> {
        T run(PreparedStatement statement) throws SQLException;
    }

    public void addRebuildListener(DatabaseRebuildListener listener) {
        rebuildListeners.add(listener);
    }

    public Path databaseFile() {
        return databaseFile;
    }

    protected Connection getConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(databaseUrl);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    protected synchronized boolean verifyDatabaseIntegrity() {
        verifiedSchema = null;
        try {
            rebuildIfNeeded();
            return true;
        } catch (SQLException e) {
            if (!DatabaseQuarantine.isCorrupt(e) || !DatabaseQuarantine.quarantine(databaseFile)) {
                LOGGER.warn("An error occurred while verifying the integrity of the database", e);
                return false;
            }
        } catch (Exception e) {
            LOGGER.warn("An unknown error occurred while verifying the integrity of the database", e);
            return false;
        }

        try {
            rebuildIfNeeded();
            notifyRebuilt();
            return true;
        } catch (Exception e) {
            LOGGER.warn("Unable to create a new database after moving the corrupt one aside", e);
            return false;
        }
    }

    private void notifyRebuilt() {
        for (DatabaseRebuildListener listener : rebuildListeners) {
            rebuildNotifier.execute(() -> {
                try {
                    listener.databaseRebuilt();
                } catch (RuntimeException e) {
                    LOGGER.error("Unable to reset the server after the database was rebuilt, restart the server", e);
                }
            });
        }
    }

    private void rebuildIfNeeded() throws SQLException {
        try {
            FilePermissions.createPrivateDirectories(databaseFile.getParent());
            if (Files.exists(databaseFile)) {
                FilePermissions.restrictFile(databaseFile);
            } else {
                FilePermissions.createPrivateFile(databaseFile);
            }
        } catch (IOException e) {
            LOGGER.warn("Unable to prepare the database location " + databaseFile.getParent(), e);
        }
        try (var connection = getConnection()) {
            DatabaseQuarantine.checkNotCorrupt(connection);
            connection.setAutoCommit(false);
            DatabaseSchema.ensureTablesExist(connection);
            DatabaseSchema.runNeededMigrations(connection);
            verifiedSchema = DatabaseSchema.fingerprint(connection);
        }
    }

    Lock readLock() {
        return databaseLock.readLock();
    }

    Connection openVerifiedConnection() throws SQLException {
        DatabaseSchema.Fingerprint expected = verifiedSchema;
        if (expected != null) {
            Connection connection = null;
            try {
                connection = getConnection();
                if (DatabaseSchema.fingerprint(connection).equals(expected)) {
                    return connection;
                }
            } catch (SQLException e) {
                LOGGER.debug("Unable to read the database schema fingerprint", e);
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

    boolean invalidateIfDamaged(SQLException e) {
        String message = String.valueOf(e.getMessage());
        boolean damaged = DatabaseQuarantine.isCorrupt(e) || message.contains("no such table") || message.contains("no such column");
        if (damaged) {
            verifiedSchema = null;
        }
        return damaged;
    }

    <T> T inTransaction(Connection connection, TransactionWork<T> work) throws SQLException {
        try {
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (SQLException e) {
            invalidateIfDamaged(e);
            DatabaseSchema.rollback(connection);
            throw e;
        }
    }

    <T> List<T> query(String action, String sql, RowMapper<T> mapper, String... params) {
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

    int update(String action, String sql, String... params) {
        return execute(action, sql, params, PreparedStatement::executeUpdate);
    }

    private <T> T execute(String action, String sql, String[] params, StatementWork<T> work) {
        databaseLock.readLock().lock();
        try {
            for (int attempt = 1; ; attempt++) {
                try (var connection = openVerifiedConnection(); var statement = connection.prepareStatement(sql)) {
                    bind(statement, params);
                    return work.run(statement);
                } catch (SQLException e) {
                    if (attempt > 1 || !invalidateIfDamaged(e)) {
                        throw new IllegalStateException("Failed to " + action, e);
                    }
                    LOGGER.warn("Database is damaged or out of date, rebuilding and retrying: {}", e.getMessage());
                }
            }
        } finally {
            databaseLock.readLock().unlock();
        }
    }

    public void snapshotTo(Path target) throws SQLException, IOException {
        databaseLock.writeLock().lock();
        try {
            if (Files.exists(target)) {
                throw new IOException("Snapshot target " + target + " already exists");
            }
            try {
                if (!Files.isRegularFile(databaseFile)) {
                    throw new IOException("Database file " + databaseFile + " does not exist");
                }
                try (var connection = openSnapshotConnection(databaseFile)) {
                    DatabaseQuarantine.checkNotCorrupt(connection);
                    try (var statement = connection.createStatement()) {
                        statement.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
                    }
                }
                verifySnapshot(target);
            } catch (SQLException | IOException | RuntimeException e) {
                Files.deleteIfExists(target);
                throw e;
            }
        } finally {
            databaseLock.writeLock().unlock();
        }
    }

    protected Connection openSnapshotConnection(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.resetOpenMode(SQLiteOpenMode.CREATE);
        return config.createConnection(SQLITE + file);
    }

    private void verifySnapshot(Path snapshot) throws SQLException, IOException {
        if (!Files.isRegularFile(snapshot)) {
            throw new IOException("Snapshot was not written to " + snapshot);
        }
        try (var connection = openSnapshotConnection(snapshot)) {
            DatabaseQuarantine.checkNotCorrupt(connection);
            DatabaseSchema.requireCompleteSchema(connection);
        }
    }

    private static void bind(PreparedStatement statement, String... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setString(i + 1, params[i]);
        }
    }
}
