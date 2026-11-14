package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.recordCurrentTime;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.PARTIAL;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;

@Service
public class ServiceBackupRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceBackupRunner.class);

    static final String PART_SUFFIX = ZipPartWriter.PART_SUFFIX;

    public record PathCopy(int index, String path, boolean copied, String error, Set<String> confirmed) {}

    public record ServiceRound(String error, List<PathCopy> paths) {}

    record ArchiveResult(int entries, Set<String> confirmed) {}

    public static final class Tally {

        private final String serviceName;
        private final String startTime = recordCurrentTime();
        private final List<String> errors = new ArrayList<>();
        private String endTime = startTime;
        private int copied;

        public Tally(String serviceName) {
            this.serviceName = serviceName;
        }

        public void add(ServiceRound round) {
            if (round.error() != null && !errors.contains(round.error())) {
                errors.add(round.error());
            }
            for (PathCopy path : round.paths()) {
                if (path.copied()) {
                    copied++;
                } else if (path.error() != null) {
                    errors.add(path.error());
                }
            }
            endTime = recordCurrentTime();
        }

        public void addCovered(int paths) {
            copied += paths;
            endTime = recordCurrentTime();
        }

        public BackupServiceResult result() {
            String status = errors.isEmpty() ? COMPLETED : copied > 0 ? PARTIAL : FAILED;
            return new BackupServiceResult(null, status, startTime, endTime, serviceName,
                errors.isEmpty() ? null : String.join("; ", errors));
        }
    }

    private final DockerService docker;
    private final ZipCompression compression;

    public ServiceBackupRunner(DockerService docker, ZipCompression compression) {
        this.docker = docker;
        this.compression = compression;
    }

    public BackupServiceResult run(ServiceDefinition service, Path runDirectory) {
        Tally tally = new Tally(service.name());
        tally.add(copyPaths(service, IntStream.range(0, service.paths().size()).boxed().toList(), runDirectory, Set.of(), List.of()));
        return tally.result();
    }

    public ServiceRound copyPaths(ServiceDefinition service, List<Integer> indexes, Path runDirectory, Set<String> excluded, List<Set<String>> covered) {
        Path serviceDirectory = runDirectory.resolve(service.name()).toAbsolutePath().normalize();
        List<PathCopy> copies = new ArrayList<>();
        try {
            Files.createDirectories(serviceDirectory);
            String containerId = docker.containerFor(service);
            for (int i = 0; i < indexes.size(); i++) {
                copies.add(copyPath(containerId, service, indexes.get(i), serviceDirectory, excluded, covered.isEmpty() ? Set.of() : covered.get(i)));
            }
            return new ServiceRound(null, copies);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[{}] Unable to prepare helper container", service.name(), e);
            for (int i = copies.size(); i < indexes.size(); i++) {
                int index = indexes.get(i);
                copies.add(new PathCopy(index, service.paths().get(index), false, null, Set.of()));
            }
            return new ServiceRound(describe(e), copies);
        }
    }

    private PathCopy copyPath(String containerId, ServiceDefinition service, int index, Path serviceDirectory, Set<String> excluded, Set<String> covered) {
        String hostPath = service.paths().get(index);
        Path part = serviceDirectory.resolve(index + PART_SUFFIX);
        try (InputStream tar = docker.copyFrom(containerId, hostPath)) {
            ArchiveResult result = archive(tar, hostPath, part, compression, excluded, covered);
            LOGGER.info("[{}] Copied {} ({} entries)", service.name(), hostPath, result.entries());
            return new PathCopy(index, hostPath, true, null, result.confirmed());
        } catch (IOException | RuntimeException e) {
            discard(part);
            LOGGER.warn("[{}] Failed to copy {}", service.name(), hostPath, e);
            return new PathCopy(index, hostPath, false, hostPath + ": " + describe(e), Set.of());
        }
    }

    static int archive(InputStream tarStream, String hostPath, Path part, ZipCompression compression) throws IOException {
        return archive(tarStream, hostPath, part, compression, Set.of(), Set.of()).entries();
    }

    static ArchiveResult archive(InputStream tarStream, String hostPath, Path part, ZipCompression compression, Set<String> excluded, Set<String> covered)
        throws IOException {
        Path source = Path.of(hostPath);
        Path base = Objects.requireNonNullElse(source.getParent(), source.getRoot());
        Set<String> confirmed = new HashSet<>();

        try (var tar = new TarArchiveInputStream(tarStream); var writer = new ZipPartWriter(part, compression)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entryName(base, source, entry.getName(), entry.isDirectory());
                if (excluded.contains(name)) {
                    writer.markSkipped(name);
                    LOGGER.debug("Skipping {} in {}, the database snapshot owns that path", name, hostPath);
                    continue;
                }
                if (writer.contains(name)) {
                    continue;
                }
                long modified = entry.getModTime().getTime();
                boolean content = false;
                if (entry.isFIFO() || entry.isCharacterDevice() || entry.isBlockDevice()) {
                    writer.markSkipped(name);
                    LOGGER.warn("Skipping special file {} in {}", entry.getName(), hostPath);
                } else if (entry.isDirectory()) {
                    writer.addEntry(name, UnixStat.DIR_FLAG | (entry.getMode() & 07777), entry.getLongUserId(), entry.getLongGroupId(), modified, 0,
                        InputStream.nullInputStream());
                    content = true;
                } else if (entry.isSymbolicLink() && covered.contains(name)) {
                    writer.markSkipped(name);
                    LOGGER.debug("Not storing symlink {} in {}, it is configured as its own path and is copied from its target", name, hostPath);
                } else if (entry.isSymbolicLink()) {
                    byte[] target = entry.getLinkName().getBytes(StandardCharsets.UTF_8);
                    writer.addEntry(name, UnixStat.LINK_FLAG | (entry.getMode() & 07777), entry.getLongUserId(), entry.getLongGroupId(), modified,
                        target.length, new ByteArrayInputStream(target));
                } else if (entry.isLink()) {
                    if (writer.addCopy(name, entryName(base, source, entry.getLinkName(), false))) {
                        content = true;
                    } else {
                        writer.markSkipped(name);
                        LOGGER.warn("Skipping hard link {} in {}, its target was skipped", entry.getName(), hostPath);
                    }
                } else if (entry.isFile()) {
                    writer.addEntry(name, UnixStat.FILE_FLAG | (entry.getMode() & 07777), entry.getLongUserId(), entry.getLongGroupId(), modified,
                        entry.getRealSize(), tar);
                    content = true;
                } else {
                    writer.markSkipped(name);
                    LOGGER.warn("Skipping unsupported entry {} in {}", entry.getName(), hostPath);
                }
                if (content) {
                    String key = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                    if (covered.contains(key)) {
                        confirmed.add(key);
                    }
                }
            }
            int entries = writer.entryCount();
            writer.complete();
            return new ArchiveResult(entries, confirmed);
        }
    }

    static String entryName(Path base, Path source, String tarName, boolean directory) throws IOException {
        Path resolved = base.resolve(tarName).normalize();
        if (!resolved.startsWith(source)) {
            throw new IOException("Archive entry escapes " + source + ": " + tarName);
        }
        String name = source.getRoot().relativize(resolved).toString();
        return directory ? name + "/" : name;
    }

    private static void discard(Path part) {
        try {
            ZipPartWriter.discard(part);
        } catch (IOException e) {
            LOGGER.warn("Unable to remove incomplete archive part {}", part, e);
        }
    }

    private static String describe(Throwable e) {
        return Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
    }
}
