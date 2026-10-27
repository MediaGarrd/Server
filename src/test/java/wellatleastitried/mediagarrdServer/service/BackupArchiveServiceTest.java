package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.RETENTION_COUNT_SETTING;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.contents;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.dir;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.file;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.link;
import static wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.tar;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.X7875_NewUnix;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.service.ServiceBackupRunnerTest.Item;

class BackupArchiveServiceTest {

    @TempDir
    Path tempDir;

    private MediaGarrdProperties properties;
    private DatabaseService db;
    private BackupArchiveService service;

    @BeforeEach
    void setUp() {
        properties = new MediaGarrdProperties();
        properties.setBackupRoot(tempDir.resolve("backups").toString());
        properties.setDatabasePath(tempDir.resolve("mediagarrd.db").toString());
        properties.setRetentionCount(2);
        db = new DatabaseService(properties);
        service = new BackupArchiveService(properties, db);
    }

    @Test
    void mergesEveryServiceIntoOneArchiveLaidOutByAbsolutePath() throws Exception {
        Path run = service.createRunDirectory();
        part(run, "radarr", 0, "/srv/radarr/config", tar(dir("config/"), file("config/config.xml", "<xml/>")));
        part(run, "nginx", 0, "/etc/nginx/sites-available/default", tar(file("default", "server {}")));
        part(run, "nginx", 1, "/etc/nginx/sites-enabled",
            tar(dir("sites-enabled/"), link("sites-enabled/default", TarConstants.LF_SYMLINK, "../sites-available/default")));

        BackupArchive archive = service.createArchive(run);
        service.removeRunDirectory(run);

        assertTrue(archive.fileName().endsWith(BackupArchiveService.ARCHIVE_SUFFIX));
        assertEquals(archive.fileName().replace(".zip", ""), archive.id());
        assertFalse(Files.exists(run));
        assertEquals(archive.id(), service.latest().orElseThrow().id());
        assertEquals(
            List.of("etc/nginx/sites-available/default", "etc/nginx/sites-enabled/", "etc/nginx/sites-enabled/default",
                "srv/radarr/config/", "srv/radarr/config/config.xml"),
            List.copyOf(contents(archive.path()).keySet()));
        assertEquals("<xml/>", contents(archive.path()).get("srv/radarr/config/config.xml"));
        try (ZipFile zip = ZipFile.builder().setPath(archive.path()).get()) {
            ZipArchiveEntry link = zip.getEntry("etc/nginx/sites-enabled/default");
            assertTrue(link.isUnixSymlink());
            assertEquals("../sites-available/default", zip.getUnixSymlink(link));
        }
    }

    @Test
    void mergeKeepsMetadataAndIncludesHardLinkParts() throws Exception {
        Path run = service.createRunDirectory();
        Item secret = file("ssh/id_rsa", "key");
        secret.entry().setMode(0100600);
        secret.entry().setUserId(1000);
        part(run, "ssh", 0, "/home/user/ssh",
            tar(dir("ssh/"), secret, link("ssh/id_rsa.bak", TarConstants.LF_LINK, "ssh/id_rsa")));

        BackupArchive archive = service.createArchive(run);

        try (ZipFile zip = ZipFile.builder().setPath(archive.path()).get()) {
            ZipArchiveEntry key = zip.getEntry("home/user/ssh/id_rsa");
            assertEquals(UnixStat.FILE_FLAG | 0600, key.getUnixMode());
            assertEquals(1000, ((X7875_NewUnix) key.getExtraField(new X7875_NewUnix().getHeaderId())).getUID());
            assertEquals(UnixStat.FILE_FLAG | 0600, zip.getEntry("home/user/ssh/id_rsa.bak").getUnixMode());
        }
        assertEquals("key", contents(archive.path()).get("home/user/ssh/id_rsa.bak"));
    }

    @Test
    void overlappingPathsAcrossServicesAppearOnce() throws Exception {
        Path run = service.createRunDirectory();
        part(run, "a", 0, "/srv/shared", tar(dir("shared/"), file("shared/x", "1")));
        part(run, "b", 0, "/srv/shared", tar(dir("shared/"), file("shared/x", "1")));

        BackupArchive archive = service.createArchive(run);

        assertEquals(Map.of("srv/shared/", "", "srv/shared/x", "1"), contents(archive.path()));
    }

    @Test
    void directoriesPrecedeTheirContentsAcrossParts() throws Exception {
        Path run = service.createRunDirectory();
        Item restricted = dir("a/");
        restricted.entry().setMode(040700);
        part(run, "child", 0, "/srv/a/b", tar(dir("b/"), file("b/x", "1")));
        part(run, "parent", 0, "/srv/a", tar(restricted, dir("a/b/"), file("a/b/x", "1"), file("a/y", "2")));

        BackupArchive archive = service.createArchive(run);

        try (ZipFile zip = ZipFile.builder().setPath(archive.path()).get()) {
            List<String> order = java.util.Collections.list(zip.getEntriesInPhysicalOrder()).stream().map(ZipArchiveEntry::getName).toList();
            assertEquals(List.of("srv/a/", "srv/a/b/", "srv/a/b/x", "srv/a/y"), order);
            assertEquals(UnixStat.DIR_FLAG | 0700, zip.getEntry("srv/a/").getUnixMode());
        }
    }

    @Test
    void ignoresStrayFilesInRunDirectory() throws Exception {
        Path run = service.createRunDirectory();
        Files.writeString(run.resolve("notes.txt"), "not a part");
        part(run, "radarr", 0, "/srv/radarr", tar(file("radarr", "x")));

        assertEquals(Map.of("srv/radarr", "x"), contents(service.createArchive(run).path()));
    }

    @Test
    void failedMergeLeavesNoArchiveBehind() throws Exception {
        Path run = service.createRunDirectory();
        Files.writeString(run.resolve("corrupt.zip"), "not a zip");

        assertThrows(IllegalStateException.class, () -> service.createArchive(run));

        try (var files = Files.list(service.rootDirectory())) {
            assertEquals(List.of("runs"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void retentionKeepsNewestArchivesAndIgnoresUnrelatedZips() throws Exception {
        Path unrelated = Files.createDirectories(service.rootDirectory()).resolve("keep-me.zip");
        Files.writeString(unrelated, "not a backup");

        for (int i = 0; i < 3; i++) {
            createArchive();
            Thread.sleep(10);
        }

        assertEquals(2, service.listArchives().size());
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void deletesArchiveAndItsRecordById() throws Exception {
        BackupArchive archive = createArchive();

        assertTrue(service.deleteById(archive.id()));

        assertFalse(service.deleteById(archive.id()));
        assertTrue(service.listArchives().isEmpty());
        assertTrue(db.fetchBackupById(archive.id()).isEmpty());
    }

    @Test
    void retentionDeletesRecordsOfRemovedArchives() throws Exception {
        BackupArchive oldest = createArchive();
        Thread.sleep(10);
        createArchive();
        Thread.sleep(10);
        BackupArchive newest = createArchive();

        assertTrue(db.fetchBackupById(oldest.id()).isEmpty());
        assertTrue(db.fetchBackupById(newest.id()).isPresent());
    }

    @Test
    void updatesAndPersistsRootDirectory() {
        Path newRoot = tempDir.resolve("elsewhere/backups");

        assertEquals(newRoot, service.updateRootDirectory(" " + newRoot + "/ "));

        assertEquals(newRoot, service.rootDirectory());
        assertTrue(Files.isDirectory(newRoot));
        assertEquals(newRoot, new BackupArchiveService(properties, db).rootDirectory());
    }

    @Test
    void createsArchivesInUpdatedRootDirectory() throws Exception {
        Path newRoot = tempDir.resolve("moved");
        service.updateRootDirectory(newRoot.toString());

        BackupArchive archive = createArchive();

        assertEquals(newRoot, archive.path().getParent());
        assertEquals(1, service.listArchives().size());
    }

    @Test
    void rejectsInvalidRootDirectories() throws Exception {
        Path file = Files.writeString(tempDir.resolve("file"), "x");

        assertThrows(IllegalArgumentException.class, () -> service.updateRootDirectory(null));
        assertThrows(IllegalArgumentException.class, () -> service.updateRootDirectory(" "));
        assertThrows(IllegalArgumentException.class, () -> service.updateRootDirectory("relative/backups"));
        assertThrows(IllegalArgumentException.class, () -> service.updateRootDirectory(file.toString()));
        assertEquals(tempDir.resolve("backups"), service.rootDirectory());
    }

    @Test
    void persistsRetentionCountAndAppliesItOnNextArchive() throws Exception {
        for (int i = 0; i < 2; i++) {
            createArchive();
            Thread.sleep(10);
        }

        service.updateSettings(null, 1);

        assertEquals(1, service.retentionCount());
        assertEquals(2, service.listArchives().size());
        createArchive();
        assertEquals(1, service.listArchives().size());
        assertEquals(1, new BackupArchiveService(properties, db).retentionCount());
    }

    @Test
    void usesPropertyRetentionUntilOneIsSaved() {
        assertEquals(2, service.retentionCount());

        properties.setRetentionCount(0);
        assertEquals(1, new BackupArchiveService(properties, db).retentionCount());
    }

    @Test
    void ignoresCorruptStoredRetention() {
        db.saveSetting(RETENTION_COUNT_SETTING, "lots");
        assertEquals(2, new BackupArchiveService(properties, db).retentionCount());

        db.saveSetting(RETENTION_COUNT_SETTING, "0");
        assertEquals(2, new BackupArchiveService(properties, db).retentionCount());
    }

    @Test
    void updatesLocationAndRetentionTogether() {
        Path newRoot = tempDir.resolve("both");

        service.updateSettings(newRoot.toString(), 5);

        BackupArchiveService restarted = new BackupArchiveService(properties, db);
        assertEquals(newRoot, restarted.rootDirectory());
        assertEquals(5, restarted.retentionCount());
    }

    @Test
    void rejectsInvalidSettingsWithoutPartialWrites() {
        assertThrows(IllegalArgumentException.class, () -> service.updateSettings(null, null));
        assertThrows(IllegalArgumentException.class, () -> service.updateSettings(null, 0));
        assertThrows(IllegalArgumentException.class, () -> service.updateSettings(tempDir.resolve("never").toString(), -1));

        assertFalse(Files.exists(tempDir.resolve("never")));
        assertEquals(tempDir.resolve("backups"), service.rootDirectory());
        assertEquals(2, service.retentionCount());
        assertTrue(db.fetchSetting(RETENTION_COUNT_SETTING).isEmpty());
    }

    @Test
    void startupCleanupRemovesStaleArtifacts() throws Exception {
        Path run = service.createRunDirectory();
        Files.writeString(run.resolve("partial.txt"), "x");
        Path tempZip = Files.writeString(service.rootDirectory().resolve("old" + BackupArchiveService.ARCHIVE_SUFFIX + ".tmp"), "x");

        service.cleanupStaleArtifactsOnStartup();

        assertFalse(Files.exists(run));
        assertFalse(Files.exists(tempZip));
    }

    private static void part(Path run, String service, int index, String hostPath, java.io.InputStream tar) throws Exception {
        Path directory = Files.createDirectories(run.resolve(service));
        ServiceBackupRunner.archive(tar, hostPath, directory.resolve(index + ServiceBackupRunner.PART_SUFFIX));
    }

    private BackupArchive createArchive() throws Exception {
        Path run = service.createRunDirectory();
        part(run, "marker", 0, "/srv/marker", tar(file("marker", "ok")));
        BackupArchive archive = service.createArchive(run);
        service.removeRunDirectory(run);
        db.addNewBackupRecord(new BackupRunResult(archive.id(), "start", "end", COMPLETED, List.of(), archive, null));
        return archive;
    }
}
