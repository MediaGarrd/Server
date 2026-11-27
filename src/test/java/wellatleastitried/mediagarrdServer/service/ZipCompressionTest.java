package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"a.zip", "a.mkv", "a.jpg", "a.flac", "a.docx", "a.zst", "dir/Photo.JPG", "x/y.tar.gz", "a.heic", "a.7z"})
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
}
