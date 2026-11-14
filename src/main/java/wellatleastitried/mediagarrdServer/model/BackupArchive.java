package wellatleastitried.mediagarrdServer.model;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

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
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(path), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }
}
