package wellatleastitried.mediagarrdServer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupArchiveTest {

    @TempDir
    Path tempDir;

    @Test
    void checksumIsFullLengthSha256Hex() throws Exception {
        Path file = Files.writeString(tempDir.resolve("a.zip"), "abc");

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", archive(file).generateChecksum());
    }

    @Test
    void checksumKeepsLeadingZeros() throws Exception {
        Path file = Files.writeString(tempDir.resolve("b.zip"), "message digest 179");

        String checksum = archive(file).generateChecksum();

        assertEquals(64, checksum.length());
    }

    @Test
    void missingFileHasEmptyChecksum() {
        assertEquals("", archive(tempDir.resolve("missing.zip")).generateChecksum());
    }

    private static BackupArchive archive(Path path) {
        return new BackupArchive("id", path.getFileName().toString(), path, 0, Instant.EPOCH);
    }
}
