package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;

@Timeout(30)
class ZipCompressionTest {

    @ParameterizedTest
    @ValueSource(ints = {-1, 10, 100})
    void rejectsLevelsOutsideZeroToNine(int level) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ZipCompression(level));

        assertTrue(e.getMessage().contains("mediagarrd.compression-level must be between 0 and 9"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 9})
    void acceptsLevelsZeroToNine(int level) {
        assertEquals(level, new ZipCompression(level).level());
    }

    @Test
    void defaultsToLevelOneFromProperties() {
        assertEquals(1, new ZipCompression(new MediaGarrdProperties()).level());
    }

    @Test
    void rejectsAnInvalidLevelFromProperties() {
        MediaGarrdProperties properties = new MediaGarrdProperties();
        properties.setCompressionLevel(12);

        assertThrows(IllegalStateException.class, () -> new ZipCompression(properties));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.zip", "a.gz", "a.tgz", "a.bz2", "a.xz", "a.zst", "a.7z", "a.rar", "a.jpg", "a.jpeg", "a.png",
        "a.gif", "a.webp", "a.mp4", "a.mkv", "a.mp3", "a.flac", "a.ogg", "a.mov", "a.avi", "a.webm", "a.m4v", "a.m4a", "a.aac",
        "a.opus", "a.heic", "a.avif", "a.lz4", "a.zstd", "a.jar", "a.docx", "a.xlsx", "a.pptx", "dir/Photo.JPG", "x/y.tar.gz"})
    void storesAlreadyCompressedFilesAtAnyNonZeroLevel(String name) {
        for (int level : new int[] {1, 9}) {
            assertTrue(new ZipCompression(level).stores(name), name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.txt", "a.db", "a.xml", "noextension", "dir.zip/file", "dir.png/readme", "a.", "gz", ".hidden"})
    void deflatesEverythingElseAtNonZeroLevels(String name) {
        for (int level : new int[] {1, 9}) {
            assertFalse(new ZipCompression(level).stores(name), name);
        }
    }

    @Test
    void levelZeroStoresEverything() {
        ZipCompression none = new ZipCompression(0);

        assertTrue(none.stores("a.txt"));
        assertTrue(none.stores("a.jpg"));
        assertTrue(none.stores("noextension"));
    }

    @Test
    void storesAlreadyCompressedFilesAtLevelOneAndReportsItsLevel() {
        ZipCompression one = new ZipCompression(1);

        assertEquals(1, one.level());
        assertTrue(one.stores("a.mkv"));
        assertFalse(one.stores("a.txt"));
        assertEquals(0, new ZipCompression(0).level());
        assertTrue(new ZipCompression(0).stores("a.txt"));
    }
}
