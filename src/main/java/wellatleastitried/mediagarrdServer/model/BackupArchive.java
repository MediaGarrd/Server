package wellatleastitried.mediagarrdServer.model;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.time.Instant;
import wellatleastitried.mediagarrdServer.utilities.Sha256;

public record BackupArchive(
    String id,
    String fileName,
    Path path,
    long sizeBytes,
    Instant createdAt,
    String checksum
) {
    public BackupArchive(String id, String fileName, Path path, long sizeBytes, Instant createdAt) {
        this(id, fileName, path, sizeBytes, createdAt, null);
    }

    public String resolvedChecksum() {
        return checksum != null ? checksum : generateChecksum();
    }

    public String generateChecksum() {
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(path), Sha256.newDigest())) {
            in.transferTo(OutputStream.nullOutputStream());
            return Sha256.hex(in.getMessageDigest().digest());
        } catch (IOException e) {
            return "";
        }
    }
}
