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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.X7875_NewUnix;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;

@Service
public class ServiceBackupRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceBackupRunner.class);

    static final String PART_SUFFIX = ".zip";
    static final String LINKS_PART_SUFFIX = "-links.zip";

    private final DockerService docker;

    public ServiceBackupRunner(DockerService docker) {
        this.docker = docker;
    }

    public BackupServiceResult run(ServiceDefinition service, Path runDirectory) {
        String startTime = recordCurrentTime();
        Path serviceDirectory = runDirectory.resolve(service.name()).toAbsolutePath().normalize();
        List<String> errors = new ArrayList<>();
        int copied = 0;

        try {
            Files.createDirectories(serviceDirectory);
            String containerId = docker.containerFor(service);
            for (int i = 0; i < service.paths().size(); i++) {
                String hostPath = service.paths().get(i);
                Path part = serviceDirectory.resolve(i + PART_SUFFIX);
                try (InputStream tar = docker.copyFrom(containerId, hostPath)) {
                    int entries = archive(tar, hostPath, part);
                    copied++;
                    LOGGER.info("[{}] Copied {} ({} entries)", service.name(), hostPath, entries);
                } catch (IOException | RuntimeException e) {
                    discard(part);
                    LOGGER.warn("[{}] Failed to copy {}", service.name(), hostPath, e);
                    errors.add(hostPath + ": " + describe(e));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[{}] Unable to prepare helper container", service.name(), e);
            errors.add(describe(e));
        }

        String status = errors.isEmpty() ? COMPLETED : copied > 0 ? PARTIAL : FAILED;
        return new BackupServiceResult(null, status, startTime, recordCurrentTime(), service.name(),
            errors.isEmpty() ? null : String.join("; ", errors));
    }

    static int archive(InputStream tarStream, String hostPath, Path part) throws IOException {
        Path source = Path.of(hostPath);
        Path base = Objects.requireNonNullElse(source.getParent(), source.getRoot());
        Set<String> names = new HashSet<>();
        Map<String, String> hardLinks = new LinkedHashMap<>();

        try (var tar = new TarArchiveInputStream(tarStream); var zip = new ZipArchiveOutputStream(part)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entryName(base, source, entry.getName(), entry.isDirectory());
                if (!names.add(name)) {
                    continue;
                }
                if (entry.isFIFO() || entry.isCharacterDevice() || entry.isBlockDevice()) {
                    names.remove(name);
                    LOGGER.warn("Skipping special file {} in {}", entry.getName(), hostPath);
                } else if (entry.isDirectory()) {
                    write(zip, entry, name, UnixStat.DIR_FLAG, null);
                } else if (entry.isSymbolicLink()) {
                    byte[] target = entry.getLinkName().getBytes(StandardCharsets.UTF_8);
                    write(zip, entry, name, UnixStat.LINK_FLAG, new ByteArrayInputStream(target));
                } else if (entry.isLink()) {
                    hardLinks.put(name, entryName(base, source, entry.getLinkName(), false));
                } else if (entry.isFile()) {
                    write(zip, entry, name, UnixStat.FILE_FLAG, tar);
                } else {
                    names.remove(name);
                    LOGGER.warn("Skipping unsupported entry {} in {}", entry.getName(), hostPath);
                }
            }
        }

        if (!hardLinks.isEmpty()) {
            writeHardLinks(part, hardLinks);
        }
        return names.size();
    }

    static String entryName(Path base, Path source, String tarName, boolean directory) throws IOException {
        Path resolved = base.resolve(tarName).normalize();
        if (!resolved.startsWith(source)) {
            throw new IOException("Archive entry escapes " + source + ": " + tarName);
        }
        String name = source.getRoot().relativize(resolved).toString();
        return directory ? name + "/" : name;
    }

    private static void write(ZipArchiveOutputStream zip, TarArchiveEntry source, String name, int type, InputStream content)
        throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setUnixMode(type | (source.getMode() & 07777));
        entry.setTime(source.getModTime().getTime());
        entry.addExtraField(owner(source.getLongUserId(), source.getLongGroupId()));
        zip.putArchiveEntry(entry);
        if (content != null) {
            content.transferTo(zip);
        }
        zip.closeArchiveEntry();
    }

    private static void writeHardLinks(Path part, Map<String, String> hardLinks) throws IOException {
        Path linksPart = linksPart(part);
        try (ZipFile written = ZipFile.builder().setPath(part).get(); var zip = new ZipArchiveOutputStream(linksPart)) {
            for (var link : hardLinks.entrySet()) {
                ZipArchiveEntry target = written.getEntry(link.getValue());
                if (target == null) {
                    throw new IOException("Hard link " + link.getKey() + " points to a missing entry " + link.getValue());
                }
                zip.addRawArchiveEntry(renamed(target, link.getKey()), written.getRawInputStream(target));
            }
        } catch (IOException e) {
            Files.deleteIfExists(linksPart);
            throw e;
        }
    }

    private static ZipArchiveEntry renamed(ZipArchiveEntry target, String name) {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setMethod(target.getMethod());
        entry.setCrc(target.getCrc());
        entry.setSize(target.getSize());
        entry.setCompressedSize(target.getCompressedSize());
        entry.setUnixMode(target.getUnixMode());
        entry.setTime(target.getTime());
        entry.setExtraFields(target.getExtraFields());
        return entry;
    }

    private static X7875_NewUnix owner(long uid, long gid) {
        X7875_NewUnix owner = new X7875_NewUnix();
        owner.setUID(uid);
        owner.setGID(gid);
        return owner;
    }

    private static void discard(Path part) {
        try {
            Files.deleteIfExists(part);
            Files.deleteIfExists(linksPart(part));
        } catch (IOException e) {
            LOGGER.warn("Unable to remove incomplete archive part {}", part, e);
        }
    }

    private static Path linksPart(Path part) {
        return part.resolveSibling(part.getFileName().toString().replace(PART_SUFFIX, LINKS_PART_SUFFIX));
    }

    private static String describe(Throwable e) {
        return Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
    }
}
