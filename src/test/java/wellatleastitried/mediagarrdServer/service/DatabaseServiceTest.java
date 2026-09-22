package wellatleastitried.mediagarrdServer.service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import wellatleastitried.mediagarrdServer.utilities.DatabaseUtils.Status;
import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.model.FetchedBackupRecord;
import wellatleastitried.mediagarrdServer.model.FetchedBackupServiceRecord;

import static org.junit.jupiter.api.Assertions.*;

import static wellatleastitried.mediagarrdServer.utilities.ServiceConstants.*;

public class DatabaseServiceTest {

    String testBackupId = "testId";
    String testStartTime = "testStartTime";
    String testEndTime = "testEndTime";
    String testFilename = "testFilename";
    Path testFilePath = Paths.get("testFilePath");
    long testFileSize = 1337;
    Instant testInstant = Instant.now();
    BackupArchive testArchive = new BackupArchive(testBackupId, testFilename, testFilePath, testFileSize, testInstant);
    String testErrorMessage = "testErrorMessage";

    String serviceOneStartTime = "testStartTime1";
    String serviceOneEndTime = "testEndTime1";
    String serviceTwoStartTime = "testStartTime2";
    String serviceTwoEndTime = "testEndTime2";
    String serviceThreeStartTime = "testStartTime3";
    String serviceThreeEndTime = null;

    private DatabaseService databaseService;
    private Connection testConnection;

    private Connection wrapUncloseable(Connection real) {
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] { Connection.class },
            (proxy, method, args) -> {
                // Need to "swallor" null so the in-memory database survives
                if ("close".equals(method.getName())) {
                    return null;
                }
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        );
    }

    @BeforeEach
    public void setUp() throws SQLException {
        testConnection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Connection uncloseable = wrapUncloseable(testConnection);
        databaseService = new DatabaseService() {
            @Override
            protected Connection getConnection() throws SQLException {
                return uncloseable;
            }
        };
    }

    @AfterEach
    public void tearDown() throws SQLException {
        testConnection.close();
    }

    @Test
    public void testDatabaseIntegrity() {
        boolean hasIntegrity = databaseService.verifyDatabaseIntegrity();
        assertEquals(true, hasIntegrity);
    }

    @Test
    public void testConnection() throws SQLException {
        try (var connection = databaseService.getConnection()) {
            assertEquals(true, connection.isValid(10));
        } catch (SQLException sE) { fail(sE); }
    }

    private BackupRunResult buildTestBackupRunResult() {
        List<BackupServiceResult> testServiceResults = List.of(
            new BackupServiceResult(testBackupId, Status.COMPLETED, serviceOneStartTime, serviceOneEndTime, SUPPORTED_SERVICES.get(Services.JELLYFIN), testErrorMessage),
            new BackupServiceResult(testBackupId, Status.PARTIAL, serviceTwoStartTime, serviceTwoEndTime, SUPPORTED_SERVICES.get(Services.SONARR), testErrorMessage),
            new BackupServiceResult(testBackupId, Status.FAILED, serviceThreeStartTime, serviceThreeEndTime, SUPPORTED_SERVICES.get(Services.QBITTORRENT), testErrorMessage)
        );
        return new BackupRunResult(
            testBackupId,
            testStartTime,
            testEndTime,
            Status.COMPLETED,
            testServiceResults,
            testArchive,
            testErrorMessage
        );
    }

    @Test
    public void testAddingBackupRecord() {
        BackupRunResult testBackupRecord = buildTestBackupRunResult();

        databaseService.addNewBackupRecord(testBackupRecord);

        FetchedBackupRecord testRecord = databaseService.fetchBackupById(testArchive.id());

        assertEquals(testBackupId, testRecord.archiveId());
        assertEquals(testFilePath.toString(), testRecord.filePath());
        assertEquals(testStartTime, testRecord.startTime());
        assertEquals(testEndTime, testRecord.endTime());
        for (FetchedBackupServiceRecord testServiceResult : testRecord.fetchedServices()) {
            if (SUPPORTED_SERVICES.get(Services.JELLYFIN).equals(testServiceResult.serviceName())) {
                assertEquals(serviceOneStartTime, testServiceResult.startTime());
                assertEquals(serviceOneEndTime, testServiceResult.endTime());
                assertEquals(Status.COMPLETED, testServiceResult.status());
                assertEquals(testErrorMessage, testServiceResult.errorMessage());

            } else if (SUPPORTED_SERVICES.get(Services.SONARR).equals(testServiceResult.serviceName())) {
                assertEquals(serviceTwoStartTime, testServiceResult.startTime());
                assertEquals(serviceTwoEndTime, testServiceResult.endTime());
                assertEquals(Status.PARTIAL, testServiceResult.status());
                assertEquals(testErrorMessage, testServiceResult.errorMessage());
            } else if (SUPPORTED_SERVICES.get(Services.QBITTORRENT).equals(testServiceResult.serviceName())) {
                assertEquals(serviceThreeStartTime, testServiceResult.startTime());
                assertEquals(serviceThreeEndTime, testServiceResult.endTime());
                assertEquals(Status.FAILED, testServiceResult.status());
                assertEquals(testErrorMessage, testServiceResult.errorMessage());
            } else {
                fail("UNREACHABLE");
            }
        }
    }

    @Test
    public void testDeletingBackupRecord() {
        BackupRunResult testBackupRecord = buildTestBackupRunResult();
        databaseService.addNewBackupRecord(testBackupRecord);
        databaseService.deleteBackupRecordById(testBackupRecord.runId());
        FetchedBackupRecord testFetchedBackupRecord = databaseService.fetchBackupById(testBackupRecord.runId());

        assertEquals(null, testFetchedBackupRecord);
    }
}
