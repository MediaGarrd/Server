package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.recordCurrentTime;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import wellatleastitried.mediagarrdServer.model.BackupServiceResult;

@Service
public class DatabaseSnapshotService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseSnapshotService.class);

    static final String SERVICE_NAME = "_mediagarrd-server";
    static final String PART_DIRECTORY = ".mediagarrd-server";
    static final String PART_NAME = "database.zip";
    private static final String SNAPSHOT_FILE = "snapshot.db";

    private final DatabaseService db;
    private final DatabaseHostPathResolver hostPaths;

    public DatabaseSnapshotService(DatabaseService db, DatabaseHostPathResolver hostPaths) {
        this.db = db;
        this.hostPaths = hostPaths;
    }

    public BackupServiceResult snapshot(Path runDirectory) {
        String startTime = recordCurrentTime();
        Path directory = runDirectory.resolve(PART_DIRECTORY);
        Path snapshot = directory.resolve(SNAPSHOT_FILE);
        Path part = directory.resolve(PART_NAME);
        try {
            if (!Files.isRegularFile(db.databaseFile())) {
                throw new IOException("Database file " + db.databaseFile() + " does not exist");
            }
            Path hostPath = hostPaths.resolve(db.databaseFile());
            Files.createDirectories(directory);
            db.snapshotTo(snapshot);
            writePart(snapshot, hostPath, part);
            LOGGER.info("[{}] Snapshotted database {} as {}", SERVICE_NAME, db.databaseFile(), hostPath);
            return new BackupServiceResult(null, COMPLETED, startTime, recordCurrentTime(), SERVICE_NAME, null);
        } catch (IOException | SQLException | RuntimeException e) {
            LOGGER.warn("[{}] Failed to snapshot database", SERVICE_NAME, e);
            deleteQuietly(part);
            return new BackupServiceResult(null, FAILED, startTime, recordCurrentTime(), SERVICE_NAME,
                Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
        } finally {
            deleteQuietly(snapshot);
        }
    }

    private void writePart(Path snapshot, Path hostPath, Path part) throws IOException {
        Map<String, Object> attributes = Files.readAttributes(db.databaseFile(), "unix:mode,uid,gid,lastModifiedTime");
        ZipArchiveEntry entry = new ZipArchiveEntry(hostPath.getRoot().relativize(hostPath).toString());
        entry.setUnixMode(UnixStat.FILE_FLAG | ((Integer) attributes.get("mode") & 07777));
        entry.setTime(((FileTime) attributes.get("lastModifiedTime")).toMillis());
        entry.addExtraField(ServiceBackupRunner.owner(((Integer) attributes.get("uid")).longValue(), ((Integer) attributes.get("gid")).longValue()));
        try (var zip = new ZipArchiveOutputStream(part)) {
            zip.putArchiveEntry(entry);
            Files.copy(snapshot, zip);
            zip.closeArchiveEntry();
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("Unable to remove {}", file, e);
        }
    }
}
