package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.BACKUP_ROOT_SETTING;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.RETENTION_COUNT_SETTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.model.BackupArchive;

@Service
public class BackupArchiveService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupArchiveService.class);

    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneOffset.UTC);
    private static final long ARCHIVE_CACHE_TTL_MS = 60_000;  // Cache archive list for 60 seconds
    static final String ARCHIVE_SUFFIX = "-media-backup.zip";

    private final DatabaseService db;
    private final AtomicReference<List<BackupArchive>> cachedArchives = new AtomicReference<>(null);
    private final AtomicLong cacheLastUpdatedMs = new AtomicLong(0);
    private volatile Path root;
    private volatile int retentionCount;

    public BackupArchiveService(MediaGarrdProperties properties, DatabaseService db) {
        this.db = db;
        this.root = Path.of(db.fetchSetting(BACKUP_ROOT_SETTING).orElse(properties.getBackupRoot()))
            .toAbsolutePath()
            .normalize();
        this.retentionCount = db.fetchSetting(RETENTION_COUNT_SETTING)
            .flatMap(BackupArchiveService::parseRetention)
            .orElse(Math.max(1, properties.getRetentionCount()));
    }

    @PostConstruct
    public void cleanupStaleArtifactsOnStartup() {
        Path root = rootDirectory();
        Path runsRoot = root.resolve("runs");

        try {
            Files.createDirectories(root);

            try (var stream = Files.list(root)) {
                stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".zip.tmp"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                            LOGGER.info("Startup cleanup: removed stale temporary archive {}", path.getFileName());
                        } catch (IOException e) {
                            LOGGER.warn("Startup cleanup: failed to remove stale temporary archive {}", path, e);
                        }
                    });
            }

            if (Files.exists(runsRoot)) {
                try (var stream = Files.list(runsRoot)) {
                    stream
                        .filter(Files::isDirectory)
                        .forEach(runDir -> {
                            try {
                                removeRunDirectory(runDir);
                                LOGGER.info("Startup cleanup: removed stale run directory {}", runDir.getFileName());
                            } catch (RuntimeException e) {
                                LOGGER.warn("Startup cleanup: failed to remove stale run directory {}", runDir, e);
                            }
                        });
                }
            }

            enforceRetention();
            LOGGER.info("Startup cleanup: retention policy enforced");
        } catch (IOException e) {
            LOGGER.warn("Startup cleanup: unable to initialize backup root {}", root, e);
        } catch (RuntimeException e) {
            LOGGER.warn("Startup cleanup: failed while enforcing retention policy", e);
        }
    }

    public Path rootDirectory() {
        return root;
    }

    public int retentionCount() {
        return retentionCount;
    }

    public synchronized void updateSettings(String location, Integer retention) {
        if (location == null && retention == null) {
            throw new IllegalArgumentException("Nothing to update, provide backupLocation and/or retentionCount");
        }
        if (retention != null && retention < 1) {
            throw new IllegalArgumentException("Retention count must be at least 1");
        }
        if (location != null) {
            updateRootDirectory(location);
        }
        if (retention != null) {
            db.saveSetting(RETENTION_COUNT_SETTING, retention.toString());
            retentionCount = retention;
            LOGGER.info("Retention count changed to {}, applied on the next backup run", retention);
        }
    }

    public Path updateRootDirectory(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Backup location is required");
        }
        Path requested = Path.of(location.strip());
        if (!requested.isAbsolute()) {
            throw new IllegalArgumentException("Backup location must be an absolute path");
        }
        Path normalized = requested.normalize();
        try {
            Files.createDirectories(normalized);
        } catch (IOException | SecurityException e) {
            throw new IllegalArgumentException("Unable to create backup location " + normalized);
        }
        if (!Files.isWritable(normalized)) {
            throw new IllegalArgumentException("Backup location is not writable: " + normalized);
        }

        db.saveSetting(BACKUP_ROOT_SETTING, normalized.toString());
        root = normalized;
        invalidateCache();
        LOGGER.info("Backup location changed to {}", normalized);
        return normalized;
    }

    public BackupArchive createArchive(Path runDirectory) {
        Instant createdAt = Instant.now();
        String fileName = FILE_DATE_FORMAT.format(createdAt) + ARCHIVE_SUFFIX;
        Path root = rootDirectory();
        Path archivePath = root.resolve(fileName);

        LOGGER.info("Creating backup archive: {} from runDir={}", fileName, runDirectory);
        try {
            Files.createDirectories(root);
            mergeParts(runDirectory, archivePath);
            enforceRetention();
            long size = Files.size(archivePath);
            LOGGER.info("Archive created: {} ({} bytes)", fileName, size);

            invalidateCache();

            return new BackupArchive(toId(fileName), fileName, archivePath, size, createdAt);
        } catch (IOException e) {
            LOGGER.error("Failed to create backup archive: {}", fileName, e);
            throw new IllegalStateException("Failed to create backup archive", e);
        }
    }

    public List<BackupArchive> listArchives() {
        long now = System.currentTimeMillis();
        long lastUpdate = cacheLastUpdatedMs.get();

        if (now - lastUpdate < ARCHIVE_CACHE_TTL_MS) {
            List<BackupArchive> cached = cachedArchives.get();
            if (cached != null) {
                LOGGER.debug("Returning cached archive list ({}ms old)", now - lastUpdate);
                return cached;
            }
        }

        // Cache miss -> rebuild list
        List<BackupArchive> archives;
        try {
            archives = scanArchives();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list archives", e);
        }

        cachedArchives.set(archives);
        cacheLastUpdatedMs.set(now);
        LOGGER.debug("Rebuilt archive cache with {} archives", archives.size());
        return archives;
    }

    public Optional<BackupArchive> latest() {
        return listArchives().stream().findFirst();
    }

    public Optional<BackupArchive> byId(String id) {
        return listArchives().stream().filter(archive -> archive.id().equals(id)).findFirst();
    }

    public boolean deleteById(String id) {
        Optional<BackupArchive> archive = byId(id);
        if (archive.isEmpty()) {
            return false;
        }

        try {
            Files.deleteIfExists(archive.get().path());
            db.deleteBackupRecordById(id);
            LOGGER.info("Archive deleted: {}", id);
            invalidateCache();
            return true;

        } catch (IOException e) {
            LOGGER.error("Failed to delete archive: {}", id, e);
            throw new IllegalStateException("Failed to delete archive " + id, e);
        }
    }

    public Path createRunDirectory() {
        Path runDir = rootDirectory().resolve("runs").resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(runDir);
            return runDir;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create run directory", e);
        }
    }

    public void removeRunDirectory(Path runDirectory) {
        if (runDirectory == null || !Files.exists(runDirectory)) {
            return;
        }

        try (var walk = Files.walk(runDirectory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to clean up run directory", e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to walk run directory", e);
        }
    }

    private void enforceRetention() throws IOException {
        List<BackupArchive> archives = scanArchives();
        for (BackupArchive archive : archives.subList(Math.min(retentionCount, archives.size()), archives.size())) {
            LOGGER.info("Retention: deleting old archive: {}", archive.fileName());
            Files.deleteIfExists(archive.path());
            db.deleteBackupRecordById(archive.id());
        }
    }

    private List<BackupArchive> scanArchives() throws IOException {
        Path root = rootDirectory();
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream
                .filter(path -> path.getFileName().toString().endsWith(ARCHIVE_SUFFIX))
                .map(this::toArchive)
                .sorted(Comparator.comparing(BackupArchive::createdAt).reversed())
                .toList();
        }
    }

    private void invalidateCache() {
        cacheLastUpdatedMs.set(0);
        cachedArchives.set(null);
    }

    private void mergeParts(Path runDirectory, Path zipPath) throws IOException {
        List<Path> parts;
        try (var walk = Files.walk(runDirectory)) {
            parts = walk
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".zip"))
                .sorted()
                .toList();
        }

        Path tempZip = zipPath.resolveSibling(zipPath.getFileName() + ".tmp");
        List<ZipFile> openParts = new ArrayList<>();
        SortedMap<String, ZipEntrySource> entries = new TreeMap<>();
        try {
            for (Path part : parts) {
                ZipFile zip = ZipFile.builder().setPath(part).get();
                openParts.add(zip);
                for (ZipArchiveEntry entry : Collections.list(zip.getEntriesInPhysicalOrder())) {
                    entries.putIfAbsent(entry.getName(), new ZipEntrySource(zip, entry));
                }
            }
            // Sorted names put every directory before its contents, so unzip applies directory modes and times
            try (var out = new ZipArchiveOutputStream(tempZip)) {
                for (ZipEntrySource source : entries.values()) {
                    out.addRawArchiveEntry(source.entry(), source.zip().getRawInputStream(source.entry()));
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tempZip);
            throw e;
        } finally {
            for (ZipFile zip : openParts) {
                zip.close();
            }
        }
        Files.move(tempZip, zipPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        LOGGER.info("Merged {} part(s) into {} entries", parts.size(), entries.size());
    }

    private record ZipEntrySource(ZipFile zip, ZipArchiveEntry entry) {}

    private BackupArchive toArchive(Path path) {
        String fileName = path.getFileName().toString();
        return new BackupArchive(
            toId(fileName),
            fileName,
            path,
            fileSize(path),
            lastModified(path)
        );
    }

    private static Optional<Integer> parseRetention(String stored) {
        try {
            return Optional.of(Integer.parseInt(stored)).filter(count -> count >= 1);
        } catch (NumberFormatException e) {
            LOGGER.warn("Ignoring invalid stored retention count: {}", stored);
            return Optional.empty();
        }
    }

    private String toId(String fileName) {
        return fileName.replace(".zip", "");
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }
}
