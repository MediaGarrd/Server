package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.dir;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.file;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.link;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.tar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
class CoveredSymlinkTest {

    private static final ZipCompression COMPRESSION = new ZipCompression(1);

    @TempDir
    Path dir;

    private Path owner(String name, ServiceBackupRunner.ArchiveResult[] result, ServiceBackupRunnerTest.Item... items) throws Exception {
        Path part = dir.resolve(name + ".part");
        result[0] = ServiceBackupRunner.archive(tar(items), "/srv/a", part, COMPRESSION, Set.of(), Set.of("srv/a/covered"));
        return part;
    }

    private Path fallback(String hostPath, ServiceBackupRunnerTest.Item... items) throws Exception {
        Path part = dir.resolve("fallback.part");
        ServiceBackupRunner.archive(tar(items), hostPath, part, COMPRESSION, Set.of(), Set.of());
        return part;
    }

    private void extract(Path archive, Path target) throws Exception {
        assumeTrue(ArchiveTestSupport.available("bsdtar"));
        Files.createDirectories(target);
        ArchiveTestSupport.run(null, "bsdtar", "-xpf", archive.toString(), "-C", target.toString());
        Path streamed = dir.resolve("streamed");
        Files.createDirectories(streamed);
        ArchiveTestSupport.run(archive, "bsdtar", "-xpf", "-", "-C", streamed.toString());
        assertEquals(Files.isSymbolicLink(target.resolve("srv/a/other")), Files.isSymbolicLink(streamed.resolve("srv/a/other")));
    }

    @Test
    void aSymlinkToAFileAtACoveredPathIsLeftOutAndTheRealFileTakesItsName() throws Exception {
        var result = new ServiceBackupRunner.ArchiveResult[1];
        Path ownerPart = owner("owner", result, dir("a/"), file("a/keep", "k"), link("a/covered", TarConstants.LF_SYMLINK, "/mnt/x"),
            link("a/other", TarConstants.LF_SYMLINK, "keep"));
        Path fallbackPart = fallback("/srv/a/covered", file("covered", "real"));

        assertTrue(result[0].confirmed().isEmpty());
        Path archive = ArchiveTestSupport.merge(dir, ownerPart, fallbackPart);
        Map<String, String> contents = ArchiveTestSupport.contents(archive);
        assertEquals("real", contents.get("srv/a/covered"));
        assertEquals("keep", contents.get("srv/a/other"));
        Path target = dir.resolve("restored");
        extract(archive, target);
        assertEquals("real", Files.readString(target.resolve("srv/a/covered")));
        assertFalse(Files.isSymbolicLink(target.resolve("srv/a/covered")));
        assertTrue(Files.isSymbolicLink(target.resolve("srv/a/other")));
        assertEquals("keep", Files.readSymbolicLink(target.resolve("srv/a/other")).toString());
    }

    @Test
    void aSymlinkToADirectoryAtACoveredPathIsLeftOutAndTheRealDirectoryIsRestored() throws Exception {
        var result = new ServiceBackupRunner.ArchiveResult[1];
        Path ownerPart = owner("owner", result, dir("a/"), file("a/keep", "k"), link("a/covered", TarConstants.LF_SYMLINK, "/mnt/dir"));
        Path fallbackPart = fallback("/srv/a/covered", dir("covered/"), file("covered/data", "real"));

        assertTrue(result[0].confirmed().isEmpty());
        Path archive = ArchiveTestSupport.merge(dir, ownerPart, fallbackPart);
        Map<String, String> contents = ArchiveTestSupport.contents(archive);
        assertFalse(contents.containsKey("srv/a/covered"));
        assertTrue(contents.containsKey("srv/a/covered/"));
        assertEquals("real", contents.get("srv/a/covered/data"));
        Path target = dir.resolve("restored");
        extract(archive, target);
        assertTrue(Files.isDirectory(target.resolve("srv/a/covered"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("real", Files.readString(target.resolve("srv/a/covered/data")));
    }

    @Test
    void aSymlinkAtANameThatIsNotCoveredIsStillStoredAsASymlink() throws Exception {
        var result = new ServiceBackupRunner.ArchiveResult[1];
        Path ownerPart = owner("owner", result, dir("a/"), link("a/other", TarConstants.LF_SYMLINK, "/mnt/x"));

        assertEquals("/mnt/x", ArchiveTestSupport.partContents(dir, ownerPart).get("srv/a/other"));
    }
}
