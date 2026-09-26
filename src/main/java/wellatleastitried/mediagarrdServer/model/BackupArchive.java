package wellatleastitried.mediagarrdServer.model;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;

public record BackupArchive(
    String id,
    String fileName,
    Path path,
    long sizeBytes,
    Instant createdAt
) {
    public String generateChecksum() {
        try {
            byte[] data = Files.readAllBytes(path);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            String checksum = new BigInteger(1, hash).toString(16);
            return checksum;
        } catch (Exception e) {}
        return "";
    }

    public int fileSize() {
        long size;
        try { size = Files.size(path); }
        catch (IOException iE) { size = 0; }

        if (size > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }

        return (int) size;

    }
}
