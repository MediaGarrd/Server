package wellatleastitried.mediagarrdServer.utilities;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FilePermissions {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilePermissions.class);

    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private FilePermissions() {}

    public static Path createPrivateDirectories(Path directory) throws IOException {
        if (!POSIX) {
            return Files.createDirectories(directory);
        }
        return Files.createDirectories(directory, directoryAttribute());
    }

    public static Path createPrivateFile(Path file) throws IOException {
        if (!POSIX) {
            return Files.createFile(file);
        }
        return Files.createFile(file, fileAttribute());
    }

    public static void restrictFile(Path file) {
        if (!POSIX) {
            return;
        }
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY_FILE);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Unable to restrict permissions of {}", LogSanitizer.clean(file), e);
        }
    }

    private static FileAttribute<Set<PosixFilePermission>> directoryAttribute() {
        return PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY);
    }

    private static FileAttribute<Set<PosixFilePermission>> fileAttribute() {
        return PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE);
    }
}
