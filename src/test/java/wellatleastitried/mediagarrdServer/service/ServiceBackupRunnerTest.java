package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.PARTIAL;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.X7875_NewUnix;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;

class ServiceBackupRunnerTest {

    private static final ServiceDefinition RADARR = new ServiceDefinition("radarr", List.of("/srv/radarr/config", "/srv/radarr/data"));

    @TempDir
    Path runDir;

    private final DockerService docker = mock(DockerService.class);
    private final ServiceBackupRunner runner = new ServiceBackupRunner(docker, new ZipCompression(1));

    @Test
    void storesDirectoryContentsUnderTheirAbsolutePaths() throws Exception {
        when(docker.containerFor(RADARR)).thenReturn("cid");
        when(docker.copyFrom("cid", "/srv/radarr/config")).thenReturn(tar(
            dir("config/"), file("config/config.xml", "<xml/>"), dir("config/empty/"), file("config/logs/app.log", "log")));
        when(docker.copyFrom("cid", "/srv/radarr/data")).thenReturn(tar(dir("data/"), file("data/radarr.db", "db")));

        BackupServiceResult result = runner.run(RADARR, runDir);

        assertEquals(COMPLETED, result.status());
        assertNull(result.errorMessage());
        Map<String, String> config = contents(runDir.resolve("radarr/0.part"));
        assertEquals(List.of("srv/radarr/config/", "srv/radarr/config/config.xml", "srv/radarr/config/empty/", "srv/radarr/config/logs/app.log"),
            List.copyOf(config.keySet()));
        assertEquals("<xml/>", config.get("srv/radarr/config/config.xml"));
        assertEquals("db", contents(runDir.resolve("radarr/1.part")).get("srv/radarr/data/radarr.db"));
    }

    @Test
    void storesOnlyTheFileWhenPathIsAFile() throws Exception {
        ServiceDefinition nginx = new ServiceDefinition("nginx", List.of("/etc/nginx/sites-available/config"));
        when(docker.containerFor(nginx)).thenReturn("cid");
        when(docker.copyFrom("cid", "/etc/nginx/sites-available/config")).thenReturn(tar(file("config", "server {}")));

        assertEquals(COMPLETED, runner.run(nginx, runDir).status());

        assertEquals(Map.of("etc/nginx/sites-available/config", "server {}"), contents(runDir.resolve("nginx/0.part")));
    }

    static Stream<Arguments> archivedEntries() {
        return Stream.of(
            Arguments.of("top-level path", "/opt",
                new Item[] {dir("opt/"), file("opt/app.conf", "x")},
                Map.of("opt/", "", "opt/app.conf", "x")),
            Arguments.of("special files are skipped", "/srv/data",
                new Item[] {dir("data/"), special("data/pipe", TarConstants.LF_FIFO), special("data/tty", TarConstants.LF_CHR), file("data/real", "ok")},
                Map.of("srv/data/", "", "srv/data/real", "ok")),
            Arguments.of("first copy of a duplicate is kept", "/srv/data",
                new Item[] {file("data/a", "first"), file("data/a", "second")},
                Map.of("srv/data/a", "first")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("archivedEntries")
    void archivesExpectedEntries(String description, String hostPath, Item[] items, Map<String, String> expected) throws Exception {
        Path part = runDir.resolve("part.zip");

        assertEquals(expected.size(), ServiceBackupRunner.archive(tar(items), hostPath, part, new ZipCompression(1)));

        assertEquals(expected, contents(part));
    }

    @Test
    void preservesModeOwnerAndModificationTime() throws Exception {
        Path part = runDir.resolve("part.zip");
        Item secret = file("config/id_rsa", "key");
        secret.entry().setMode(0100600);
        secret.entry().setUserId(1000);
        secret.entry().setGroupId(1001);
        secret.entry().setModTime(new Date(1_700_000_001_000L));
        Item folder = dir("config/");
        folder.entry().setMode(040750);

        ServiceBackupRunner.archive(tar(folder, secret), "/home/user/config", part, new ZipCompression(1));

        try (ZipFile zip = ZipFile.builder().setPath(merged(part)).get()) {
            ZipArchiveEntry key = zip.getEntry("home/user/config/id_rsa");
            assertEquals(UnixStat.FILE_FLAG | 0600, key.getUnixMode());
            assertEquals(1_700_000_001_000L, key.getTime());
            X7875_NewUnix owner = (X7875_NewUnix) key.getExtraField(new X7875_NewUnix().getHeaderId());
            assertEquals(1000, owner.getUID());
            assertEquals(1001, owner.getGID());
            assertEquals(UnixStat.DIR_FLAG | 0750, zip.getEntry("home/user/config/").getUnixMode());
        }
    }

    @ParameterizedTest(name = "level {0}")
    @ValueSource(ints = {0, 1, 9})
    @Timeout(30)
    void picksEntryMethodsByLevelAndExtensionAndRoundTripsContents(int level) throws Exception {
        Path part = runDir.resolve("part.zip");
        String text = "compressible text ".repeat(2000);
        Item notes = file("data/notes.txt", text);
        notes.entry().setMode(0100640);
        notes.entry().setUserId(1000);
        notes.entry().setGroupId(1001);
        notes.entry().setModTime(new Date(1_700_000_000_000L));
        Item movie = file("data/movie.MKV", text);
        movie.entry().setMode(0100600);
        movie.entry().setUserId(7);
        movie.entry().setGroupId(8);

        ServiceBackupRunner.archive(tar(dir("data/"), notes, movie, file("data/empty.txt", "")), "/srv/data", part, new ZipCompression(level));

        assertEquals(text, contents(part).get("srv/data/notes.txt"));
        assertEquals(text, contents(part).get("srv/data/movie.MKV"));
        assertEquals("", contents(part).get("srv/data/empty.txt"));
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        try (ZipFile zip = ZipFile.builder().setPath(merged(part)).get()) {
            ZipArchiveEntry stored = zip.getEntry("srv/data/movie.MKV");
            assertEquals(ZipEntry.STORED, stored.getMethod());
            assertEquals(text.length(), stored.getSize());
            assertEquals(text.length(), stored.getCompressedSize());
            assertEquals(crc.getValue(), stored.getCrc());
            assertEquals(UnixStat.FILE_FLAG | 0600, stored.getUnixMode());
            X7875_NewUnix owner = (X7875_NewUnix) stored.getExtraField(new X7875_NewUnix().getHeaderId());
            assertEquals(7, owner.getUID());
            assertEquals(8, owner.getGID());

            ZipArchiveEntry notesEntry = zip.getEntry("srv/data/notes.txt");
            assertEquals(level == 0 ? ZipEntry.STORED : ZipEntry.DEFLATED, notesEntry.getMethod());
            assertEquals(level == 0, notesEntry.getCompressedSize() == notesEntry.getSize());
            assertEquals(UnixStat.FILE_FLAG | 0640, notesEntry.getUnixMode());
            assertEquals(1_700_000_000_000L, notesEntry.getTime());
            X7875_NewUnix notesOwner = (X7875_NewUnix) notesEntry.getExtraField(new X7875_NewUnix().getHeaderId());
            assertEquals(1000, notesOwner.getUID());
            assertEquals(1001, notesOwner.getGID());
            assertEquals(UnixStat.DIR_FLAG | 0755, zip.getEntry("srv/data/").getUnixMode());
        }
    }

    @Test
    @Timeout(30)
    void higherLevelsProduceSmallerPartsForCompressibleData() throws Exception {
        String text = "compressible text ".repeat(20000);
        Path none = runDir.resolve("none.zip");
        Path fast = runDir.resolve("fast.zip");
        Path best = runDir.resolve("best.zip");

        ServiceBackupRunner.archive(tar(file("f/a.txt", text)), "/srv/f", none, new ZipCompression(0));
        ServiceBackupRunner.archive(tar(file("f/a.txt", text)), "/srv/f", fast, new ZipCompression(1));
        ServiceBackupRunner.archive(tar(file("f/a.txt", text)), "/srv/f", best, new ZipCompression(9));

        assertTrue(Files.size(none) > text.length());
        assertTrue(Files.size(fast) < text.length() / 10);
        assertTrue(Files.size(best) <= Files.size(fast));
    }

    @Test
    @Timeout(30)
    void hardLinksToStoredAndDeflatedEntriesKeepTheirMethods() throws Exception {
        Path part = runDir.resolve("part.zip");
        Item stored = file("data/clip.mp4", "video".repeat(500));
        Item deflated = file("data/log.txt", "text".repeat(500));
        Item linkStored = link("data/clip-link.mp4", TarConstants.LF_LINK, "data/clip.mp4");
        Item linkDeflated = link("data/log-link.txt", TarConstants.LF_LINK, "data/log.txt");

        ServiceBackupRunner.archive(tar(dir("data/"), stored, deflated, linkStored, linkDeflated), "/srv/data", part, new ZipCompression(1));

        try (ZipFile zip = ZipFile.builder().setPath(merged(part)).get()) {
            assertEquals(ZipEntry.STORED, zip.getEntry("srv/data/clip-link.mp4").getMethod());
            assertEquals(ZipEntry.DEFLATED, zip.getEntry("srv/data/log-link.txt").getMethod());
        }
        assertEquals("video".repeat(500), contents(part).get("srv/data/clip-link.mp4"));
        assertEquals("text".repeat(500), contents(part).get("srv/data/log-link.txt"));
    }

    @Test
    void storesSymbolicLinksAsLinks() throws Exception {
        Path part = runDir.resolve("part.zip");
        Item symlink = link("sites-enabled/default", TarConstants.LF_SYMLINK, "/etc/nginx/sites-available/default");

        ServiceBackupRunner.archive(tar(dir("sites-enabled/"), symlink), "/etc/nginx/sites-enabled", part, new ZipCompression(1));

        try (ZipFile zip = ZipFile.builder().setPath(merged(part)).get()) {
            ZipArchiveEntry entry = zip.getEntry("etc/nginx/sites-enabled/default");
            assertTrue(entry.isUnixSymlink());
            assertEquals("/etc/nginx/sites-available/default", zip.getUnixSymlink(entry));
        }
    }

    @Test
    void storesHardLinksAsRegularCopiesOfTheirTarget() throws Exception {
        Path part = runDir.resolve("0.part");
        Item hardLink = link("data/link", TarConstants.LF_LINK, "data/original");

        assertEquals(3, ServiceBackupRunner.archive(tar(dir("data/"), file("data/original", "same"), hardLink), "/srv/data", part, new ZipCompression(1)));

        assertEquals(Map.of("srv/data/", "", "srv/data/original", "same", "srv/data/link", "same"), contents(part));
    }

    @Test
    void excludedNamesAndHardLinksToThemAreSkipped() throws Exception {
        Path part = runDir.resolve("0.part");
        Item hardLink = link("data/db-link", TarConstants.LF_LINK, "data/live.db");

        ServiceBackupRunner.ArchiveResult result = ServiceBackupRunner.archive(
            tar(dir("data/"), file("data/live.db", "live"), hardLink, file("data/keep", "k")), "/srv/data", part, new ZipCompression(1),
            java.util.Set.of("srv/data/live.db"), java.util.Set.of());

        assertEquals(2, result.entries());
        assertEquals(Map.of("srv/data/", "", "srv/data/keep", "k"), contents(part));
    }

    @Test
    void aHardLinkToASkippedSpecialFileIsSkippedInsteadOfFailingThePath() throws Exception {
        Path part = runDir.resolve("0.part");

        int entries = ServiceBackupRunner.archive(
            tar(dir("data/"), special("data/pipe", TarConstants.LF_FIFO), link("data/pipe2", TarConstants.LF_LINK, "data/pipe"), file("data/f", "x")),
            "/srv/data", part, new ZipCompression(1));

        assertEquals(2, entries);
        assertEquals(Map.of("srv/data/", "", "srv/data/f", "x"), contents(part));
    }

    @Test
    void hardLinksToHardLinksResolveAndUnknownTargetsFailThePath() throws Exception {
        Path part = runDir.resolve("0.part");

        ServiceBackupRunner.archive(tar(file("data/a", "same"), link("data/b", TarConstants.LF_LINK, "data/a"), link("data/c", TarConstants.LF_LINK, "data/b")),
            "/srv/data", part, new ZipCompression(1));

        assertEquals(Map.of("srv/data/a", "same", "srv/data/b", "same", "srv/data/c", "same"), contents(part));
        Path broken = runDir.resolve("broken.part");
        assertThrows(IOException.class,
            () -> ServiceBackupRunner.archive(tar(link("data/b", TarConstants.LF_LINK, "data/nothing")), "/srv/data", broken, new ZipCompression(1)));
        assertFalse(Files.exists(broken));
        assertFalse(Files.exists(ZipPartWriter.indexOf(broken)));
    }

    @Test
    void confirmsCoveredPathsOnlyWhenTheirDirectoryOrFileWasStored() throws Exception {
        Path part = runDir.resolve("0.part");

        ServiceBackupRunner.ArchiveResult result = ServiceBackupRunner.archive(
            tar(dir("a/"), dir("a/dir/"), file("a/file", "f"), link("a/link", TarConstants.LF_SYMLINK, "/mnt/x"), dir("a/other/")),
            "/srv/a", part, new ZipCompression(1),
            java.util.Set.of(), java.util.Set.of("srv/a/dir", "srv/a/file", "srv/a/link", "srv/a/missing", "srv/a"));

        assertEquals(java.util.Set.of("srv/a/dir", "srv/a/file", "srv/a"), result.confirmed());
    }

    @Test
    void reportsPartialAndDiscardsOnlyTheFailedPath() throws Exception {
        when(docker.containerFor(RADARR)).thenReturn("cid");
        when(docker.copyFrom("cid", "/srv/radarr/config")).thenReturn(tar(file("config/config.xml", "<xml/>")));
        when(docker.copyFrom("cid", "/srv/radarr/data")).thenReturn(truncatedTar());

        BackupServiceResult result = runner.run(RADARR, runDir);

        assertEquals(PARTIAL, result.status());
        assertTrue(result.errorMessage().startsWith("/srv/radarr/data: "));
        assertTrue(Files.exists(runDir.resolve("radarr/0.part")));
        assertFalse(Files.exists(runDir.resolve("radarr/1.part")));
        assertFalse(Files.exists(runDir.resolve("radarr/1.part.partial")));
        assertFalse(Files.exists(runDir.resolve("radarr/1.part.idx")));
    }

    @Test
    void reportsFailedWhenNoPathsCopy() {
        when(docker.containerFor(RADARR)).thenReturn("cid");
        when(docker.copyFrom("cid", "/srv/radarr/config")).thenThrow(new RuntimeException("a"));
        when(docker.copyFrom("cid", "/srv/radarr/data")).thenThrow(new RuntimeException("b"));

        BackupServiceResult result = runner.run(RADARR, runDir);

        assertEquals(FAILED, result.status());
        assertEquals("/srv/radarr/config: a; /srv/radarr/data: b", result.errorMessage());
    }

    @Test
    void reportsFailedWhenContainerCannotBePrepared() {
        when(docker.containerFor(RADARR)).thenThrow(new IllegalStateException("bind source path does not exist"));

        BackupServiceResult result = runner.run(RADARR, runDir);

        assertEquals(FAILED, result.status());
        assertEquals("bind source path does not exist", result.errorMessage());
    }

    @Test
    void rejectsEntriesOutsideTheBackedUpPath() {
        Path part = runDir.resolve("part.zip");

        assertThrows(IOException.class, () -> ServiceBackupRunner.archive(tar(file("../escaped.txt", "x")), "/srv/data", part, new ZipCompression(1)));
        assertThrows(IOException.class, () -> ServiceBackupRunner.archive(tar(file("other/file", "x")), "/srv/data", part, new ZipCompression(1)));
    }

    @Test
    void rejectsHardLinksOutsideTheBackedUpPath() {
        Item hardLink = link("data/link", TarConstants.LF_LINK, "../etc/shadow");

        assertThrows(IOException.class,
            () -> ServiceBackupRunner.archive(tar(dir("data/"), hardLink), "/srv/data", runDir.resolve("part.zip"), new ZipCompression(1)));
    }

    @Test
    void buildsEntryNamesFromAbsolutePaths() throws IOException {
        assertEquals("etc/nginx/nginx.conf", ServiceBackupRunner.entryName(Path.of("/etc/nginx"), Path.of("/etc/nginx/nginx.conf"), "nginx.conf", false));
        assertEquals("etc/nginx/", ServiceBackupRunner.entryName(Path.of("/etc"), Path.of("/etc/nginx"), "nginx/", true));
        assertEquals("srv/", ServiceBackupRunner.entryName(Path.of("/"), Path.of("/srv"), "srv/", true));
    }

    static Path merged(Path part) throws IOException {
        return ArchiveTestSupport.merge(part.getParent(), part);
    }

    static Map<String, String> contents(Path part) throws IOException {
        return ArchiveTestSupport.contents(merged(part));
    }

    record Item(TarArchiveEntry entry, byte[] content) {}

    static Item dir(String name) {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setMode(040755);
        return new Item(entry, null);
    }

    static Item file(String name, String content) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setMode(0100644);
        entry.setSize(data.length);
        entry.setModTime(new Date(0));
        return new Item(entry, data);
    }

    static Item link(String name, byte type, String target) {
        TarArchiveEntry entry = new TarArchiveEntry(name, type);
        entry.setLinkName(target);
        return new Item(entry, null);
    }

    static Item special(String name, byte type) {
        return new Item(new TarArchiveEntry(name, type), null);
    }

    static InputStream tar(Item... items) throws IOException {
        return new ByteArrayInputStream(tarBytes(items));
    }

    private static byte[] tarBytes(Item... items) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var out = new TarArchiveOutputStream(bytes)) {
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Item item : items) {
                out.putArchiveEntry(item.entry());
                if (item.content() != null) {
                    out.write(item.content());
                }
                out.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static InputStream truncatedTar() throws IOException {
        byte[] full = tarBytes(file("data/radarr.db", "x".repeat(4096)));
        return new ByteArrayInputStream(Arrays.copyOf(full, 1024));
    }
}
