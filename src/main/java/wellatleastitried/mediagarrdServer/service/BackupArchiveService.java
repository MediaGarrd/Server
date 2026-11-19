package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.BACKUP_ROOT_SETTING;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.RETENTION_COUNT_SETTING;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.repository.BackupRecordRepository;
import wellatleastitried.mediagarrdServer.repository.SettingsRepository;
import wellatleastitried.mediagarrdServer.utilities.FilePermissions;
import wellatleastitried.mediagarrdServer.utilities.LogSanitizer;

@Service
public class BackupArchiveService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupArchiveService.class);

    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneOffset.UTC);
    private static final long ARCHIVE_CACHE_TTL_MS = 60_000;  // Cache archive list for 60 seconds
    static final String ARCHIVE_SUFFIX = "-media-backup.zip";
    static final String ZIP_EXTENSION = ".zip";
    static final String RUNS_DIRECTORY = ".mediagarrd-runs";
    static final String TEMP_SUFFIX = ARCHIVE_SUFFIX + ".tmp";
    static final int MAX_LOCATION_LENGTH = 4096;

    private final MediaGarrdProperties properties;
    private final SettingsRepository settings;
    private final BackupRecordRepository backups;
    private final AtomicReference<ArchiveSnapshot> archiveCache = new AtomicReference<>(null);
    private record ArchiveSnapshot(List<BackupArchive> archives, long loadedAtMs) {}

    private volatile Path root;
    private volatile int retentionCount;

    public BackupArchiveService(MediaGarrdProperties properties, SettingsRepository settings, BackupRecordRepository backups) {
        this.properties = properties;
        this.settings = settings;
        this.backups = backups;
        loadSettings();
    }

    private void loadSettings() {
        this.root = Path.of(settings.fetchSetting(BACKUP_ROOT_SETTING).orElse(properties.getBackupRoot()))
            .toAbsolutePath()
            .normalize();
        this.retentionCount = settings.fetchSetting(RETENTION_COUNT_SETTING)
            .flatMap(BackupArchiveService::parseRetention)
            .orElse(Math.max(1, properties.getRetentionCount()));
    }

    public synchronized void reloadFromSettings() {
        loadSettings();
        invalidateCache();
        LOGGER.info("Backup location and retention reloaded from the settings: location={}, retentionCount={}", LogSanitizer.clean(root), retentionCount);
    }

    @PostConstruct
    public void cleanupStaleArtifactsOnStartup() {
        Path root = rootDirectory();
        Path runsRoot = root.resolve(RUNS_DIRECTORY);

        try {
            FilePermissions.createPrivateDirectories(root);

            try (var stream = Files.list(root)) {
                stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(TEMP_SUFFIX))
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
            settings.saveSetting(RETENTION_COUNT_SETTING, retention.toString());
            retentionCount = retention;
            LOGGER.info("Retention count changed to {}, applied on the next backup run", retention);
        }
    }

    public Path updateRootDirectory(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Backup location is required");
        }
        if (location.length() > MAX_LOCATION_LENGTH) {
            throw new IllegalArgumentException("Backup location must be at most " + MAX_LOCATION_LENGTH + " characters");
        }
        if (location.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Backup location must not contain control characters");
        }
        Path requested = Path.of(location.strip());
        if (!requested.isAbsolute()) {
            throw new IllegalArgumentException("Backup location must be an absolute path");
        }
        Path normalized = requested.normalize();
        try {
            FilePermissions.createPrivateDirectories(normalized);
        } catch (IOException | SecurityException e) {
            throw new IllegalArgumentException("Unable to create backup location " + normalized);
        }
        if (!Files.isWritable(normalized)) {
            throw new IllegalArgumentException("Backup location is not writable: " + normalized);
        }

        settings.saveSetting(BACKUP_ROOT_SETTING, normalized.toString());
        root = normalized;
        invalidateCache();
        LOGGER.info("Backup location changed to {}", LogSanitizer.clean(normalized));
        return normalized;
    }

    public BackupArchive createArchive(Path runDirectory) {
        Instant createdAt = Instant.now();
        String fileName = FILE_DATE_FORMAT.format(createdAt) + ARCHIVE_SUFFIX;
        Path root = rootDirectory();
        Path archivePath = root.resolve(fileName);

        LOGGER.info("Creating backup archive: {} from runDir={}", fileName, runDirectory);
        try {
            FilePermissions.createPrivateDirectories(root);
            String checksum = mergeParts(runDirectory, archivePath);
            enforceRetention();
            long size = Files.size(archivePath);
            LOGGER.info("Archive created: {} ({} bytes)", fileName, size);

            invalidateCache();

            return new BackupArchive(toId(fileName), fileName, archivePath, size, createdAt, checksum);
        } catch (IOException e) {
            LOGGER.error("Failed to create backup archive: {}", fileName, e);
            throw new IllegalStateException("Failed to create backup archive", e);
        }
    }

    public List<BackupArchive> listArchives() {
        long now = System.currentTimeMillis();
        ArchiveSnapshot cached = archiveCache.get();

        if (cached != null && now - cached.loadedAtMs() < ARCHIVE_CACHE_TTL_MS) {
            LOGGER.debug("Returning cached archive list ({}ms old)", now - cached.loadedAtMs());
            return cached.archives();
        }

        // Cache miss -> rebuild list
        List<BackupArchive> archives;
        try {
            archives = scanArchives();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list archives", e);
        }

        archiveCache.set(new ArchiveSnapshot(archives, now));
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
            backups.deleteBackupRecordById(id);
            LOGGER.info("Archive deleted: {}", id);
            invalidateCache();
            return true;

        } catch (IOException e) {
            LOGGER.error("Failed to delete archive: {}", id, e);
            throw new IllegalStateException("Failed to delete archive " + id, e);
        }
    }

    public Path createRunDirectory() {
        Path runDir = rootDirectory().resolve(RUNS_DIRECTORY).resolve(UUID.randomUUID().toString());
        try {
            FilePermissions.createPrivateDirectories(runDir);
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
            if (backups.fetchBackupById(archive.id()).isEmpty()) {
                LOGGER.info("Retention: keeping {} because this server has no record of creating it", archive.fileName());
                continue;
            }
            LOGGER.info("Retention: deleting old archive: {}", archive.fileName());
            Files.deleteIfExists(archive.path());
            backups.deleteBackupRecordById(archive.id());
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
        archiveCache.set(null);
    }

    private String mergeParts(Path runDirectory, Path zipPath) throws IOException {
        List<Path> parts;
        try (var walk = Files.walk(runDirectory)) {
            parts = walk
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(ZipPartWriter.PART_SUFFIX))
                .sorted()
                .toList();
        }

        Path tempZip = zipPath.resolveSibling(zipPath.getFileName() + ".tmp");
        Files.deleteIfExists(tempZip);
        FilePermissions.createPrivateFile(tempZip);
        Path snapshotDirectory = runDirectory.resolve(DatabaseSnapshotService.PART_DIRECTORY);
        ZipPartMerger.Result result;
        try {
            result = ZipPartMerger.merge(
                parts.stream().filter(path -> path.startsWith(snapshotDirectory)).toList(),
                parts.stream().filter(path -> !path.startsWith(snapshotDirectory)).toList(),
                tempZip, runDirectory);
            syncFile(tempZip);
            Files.move(tempZip, zipPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tempZip);
            throw e;
        }
        syncDirectory(zipPath.getParent());
        LOGGER.info("Merged {} part(s) into {} entries", parts.size(), result.entries());
        return result.checksum();
    }

    static void syncFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    static void syncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not sync directory {}: {}", directory, e.toString());
        }
    }

    private BackupArchive toArchive(Path path) {
        String fileName = path.getFileName().toString();
        return new BackupArchive(
            toId(fileName),
            fileName,
            path,
            fileSize(path),
            createdAt(path)
        );
    }

    private Instant createdAt(Path path) {
        String fileName = path.getFileName().toString();
        String stamp = fileName.substring(0, fileName.length() - ARCHIVE_SUFFIX.length());
        try {
            return FILE_DATE_FORMAT.parse(stamp, Instant::from);
        } catch (DateTimeParseException e) {
            return lastModified(path);
        }
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
        return fileName.endsWith(ZIP_EXTENSION) ? fileName.substring(0, fileName.length() - ZIP_EXTENSION.length()) : fileName;
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
