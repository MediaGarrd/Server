package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.recordCurrentTime;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import wellatleastitried.mediagarrdServer.model.BackupServiceResult;

@Service
public class DatabaseSnapshotService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseSnapshotService.class);

    static final String SERVICE_NAME = "_mediagarrd-server";
    static final String PART_DIRECTORY = ".mediagarrd-server";
    static final String PART_NAME = "database" + ZipPartWriter.PART_SUFFIX;
    private static final List<String> DATABASE_SIDECAR_SUFFIXES = List.of("", "-journal", "-wal", "-shm");
    private static final String SNAPSHOT_FILE = "snapshot.db";

    private final DatabaseService db;
    private final DatabaseHostPathResolver hostPaths;
    private final ZipCompression compression;

    public DatabaseSnapshotService(DatabaseService db, DatabaseHostPathResolver hostPaths, ZipCompression compression) {
        this.db = db;
        this.hostPaths = hostPaths;
        this.compression = compression;
    }

    public record Outcome(BackupServiceResult result, Path hostPath) {}

    public BackupServiceResult snapshot(Path runDirectory) {
        return snapshotWithHostPath(runDirectory).result();
    }

    public Outcome snapshotWithHostPath(Path runDirectory) {
        String startTime = recordCurrentTime();
        Path directory = runDirectory.resolve(PART_DIRECTORY);
        Path snapshot = directory.resolve(SNAPSHOT_FILE);
        Path part = directory.resolve(PART_NAME);
        Path hostPath = null;
        try {
            if (!Files.isRegularFile(db.databaseFile())) {
                throw new IOException("Database file " + db.databaseFile() + " does not exist");
            }
            hostPath = hostPaths.resolve(db.databaseFile());
            Files.createDirectories(directory);
            db.snapshotTo(snapshot);
            writePart(snapshot, hostPath, part);
            LOGGER.info("[{}] Snapshotted database {} as {}", SERVICE_NAME, db.databaseFile(), hostPath);
            return new Outcome(new BackupServiceResult(null, COMPLETED, startTime, recordCurrentTime(), SERVICE_NAME, null), hostPath);
        } catch (IOException | SQLException | RuntimeException e) {
            LOGGER.warn("[{}] Failed to snapshot database", SERVICE_NAME, e);
            discardQuietly(part);
            return new Outcome(new BackupServiceResult(null, FAILED, startTime, recordCurrentTime(), SERVICE_NAME,
                Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName())), hostPath);
        } finally {
            deleteQuietly(snapshot);
        }
    }

    static String entryName(Path hostPath) {
        return hostPath.getRoot().relativize(hostPath).toString();
    }

    static Set<String> reservedNames(Path hostPath) {
        String name = entryName(hostPath);
        return DATABASE_SIDECAR_SUFFIXES.stream().map(suffix -> name + suffix).collect(Collectors.toUnmodifiableSet());
    }

    private void writePart(Path snapshot, Path hostPath, Path part) throws IOException {
        Map<String, Object> attributes = Files.readAttributes(db.databaseFile(), "unix:mode,uid,gid,lastModifiedTime");
        try (var writer = new ZipPartWriter(part, compression); InputStream content = Files.newInputStream(snapshot)) {
            writer.addEntry(entryName(hostPath), UnixStat.FILE_FLAG | ((Integer) attributes.get("mode") & 07777),
                ((Integer) attributes.get("uid")).longValue(), ((Integer) attributes.get("gid")).longValue(),
                ((FileTime) attributes.get("lastModifiedTime")).toMillis(), Files.size(snapshot), content);
            writer.complete();
        }
    }

    private static void discardQuietly(Path part) {
        try {
            ZipPartWriter.discard(part);
        } catch (IOException e) {
            LOGGER.warn("Unable to remove {}", part, e);
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
