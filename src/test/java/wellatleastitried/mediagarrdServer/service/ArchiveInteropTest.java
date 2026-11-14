package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.dir;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.file;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.link;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.tar;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Date;

import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.Item;

@Timeout(120)
class ArchiveInteropTest {

    private static final long MTIME = 1_700_000_001_000L;

    @TempDir
    Path dir;

    private Path archive() throws Exception {
        Item folder = dir("data/");
        folder.entry().setMode(040750);
        folder.entry().setModTime(new Date(MTIME));
        folder.entry().setUserId(1000);
        folder.entry().setGroupId(1001);
        Item secret = file("data/id_rsa", "key");
        secret.entry().setMode(0100600);
        secret.entry().setModTime(new Date(MTIME));
        secret.entry().setUserId(1000);
        secret.entry().setGroupId(1001);
        Item text = file("data/café ünï.txt", "compressible ".repeat(1000));
        text.entry().setModTime(new Date(MTIME + 2000));
        text.entry().setUserId(5);
        text.entry().setGroupId(6);
        Item empty = dir("data/empty/");
        empty.entry().setModTime(new Date(MTIME));
        Item symlink = link("data/latest", TarConstants.LF_SYMLINK, "id_rsa");
        symlink.entry().setModTime(new Date(MTIME));
        Item hardLink = link("data/id_rsa.bak", TarConstants.LF_LINK, "data/id_rsa");
        Path part = dir.resolve("0.part");
        ServiceBackupRunner.archive(tar(folder, secret, text, empty, symlink, hardLink), "/srv/data", part, new ZipCompression(1));
        return ArchiveTestSupport.merge(dir, part);
    }

    @Test
    void bsdtarRestoresContentsModesTimesAndSymlinksFromSeekableAndStreamedArchives() throws Exception {
        assumeTrue(ArchiveTestSupport.available("bsdtar"));
        Path archive = archive();
        for (boolean streamed : new boolean[] {false, true}) {
            Path target = Files.createDirectories(dir.resolve(streamed ? "streamed" : "seekable"));
            if (streamed) {
                ArchiveTestSupport.run(archive, "bsdtar", "-xpf", "-", "-C", target.toString());
            } else {
                ArchiveTestSupport.run(null, "bsdtar", "-xpf", archive.toString(), "-C", target.toString());
            }

            Path data = target.resolve("srv/data");
            assertEquals("key", Files.readString(data.resolve("id_rsa")));
            assertEquals("key", Files.readString(data.resolve("id_rsa.bak")));
            assertEquals("compressible ".repeat(1000), Files.readString(data.resolve("café ünï.txt"), StandardCharsets.UTF_8));
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(data.resolve("id_rsa"))));
            assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(data)));
            assertEquals(MTIME / 1000, Files.getLastModifiedTime(data.resolve("id_rsa")).toMillis() / 1000);
            assertEquals((MTIME + 2000) / 1000, Files.getLastModifiedTime(data.resolve("café ünï.txt")).toMillis() / 1000);
            assertEquals(MTIME / 1000, Files.getLastModifiedTime(data).toMillis() / 1000);
            assertTrue(Files.isSymbolicLink(data.resolve("latest")));
            assertEquals("id_rsa", Files.readSymbolicLink(data.resolve("latest")).toString());
            assertTrue(Files.isDirectory(data.resolve("empty")));
        }
    }

    @Test
    void listingsShowNumericOwnersFromTheLocalHeaders() throws Exception {
        assumeTrue(ArchiveTestSupport.available("bsdtar"));
        Path archive = archive();

        for (String listing : new String[] {
            ArchiveTestSupport.run(null, "bsdtar", "-tvf", archive.toString(), "--numeric-owner"),
            ArchiveTestSupport.run(archive, "bsdtar", "-tvf", "-", "--numeric-owner")}) {
            assertTrue(listing.matches("(?s).*-rw-------\\s+\\d+\\s+1000\\s+1001\\s+3\\s.*id_rsa\\n.*"), listing);
            assertTrue(listing.matches("(?s).*\\s5\\s+6\\s.*caf.*"), listing);
            assertTrue(listing.matches("(?s).*drwxr-x---\\s+\\d+\\s+1000\\s+1001.*srv/data/\\n.*"), listing);
        }
    }

    @Test
    void everyReaderAcceptsTheArchive() throws Exception {
        ZipPartWriterTest.verifyWithTools(archive(), 6);
    }
}
