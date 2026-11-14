package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.X5455_ExtendedTimestamp;
import org.apache.commons.compress.archivers.zip.X7875_NewUnix;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ZipPartWriterTest {

    private static final int FILE = UnixStat.FILE_FLAG | 0644;
    private static final int DIRECTORY = UnixStat.DIR_FLAG | 0755;

    @TempDir
    Path dir;

    private static InputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private void add(ZipPartWriter writer, String name, String text) throws IOException {
        writer.addEntry(name, FILE, 1000, 1001, 1_700_000_001_000L, text.getBytes(StandardCharsets.UTF_8).length, bytes(text));
    }

    @Test
    void writesEntriesWithMetadataThatReadersAndExactTimesRoundTrip() throws Exception {
        Path part = dir.resolve("0.part");
        String text = "compressible text ".repeat(500);
        try (var writer = new ZipPartWriter(part, new ZipCompression(1))) {
            writer.addEntry("srv/data/", DIRECTORY, 0, 0, 1_700_000_001_000L, 0, bytes(""));
            writer.addEntry("srv/data/notes.txt", UnixStat.FILE_FLAG | 0640, 1000, 1001, 1_700_000_001_000L, text.length(), bytes(text));
            writer.addEntry("srv/data/movie.mkv", UnixStat.FILE_FLAG | 0400, 7, 8, 1_700_000_003_000L, text.length(), bytes(text));
            writer.addEntry("srv/data/empty.txt", FILE, 1, 2, 1_700_000_005_000L, 0, bytes(""));
            writer.complete();
            assertEquals(4, writer.entryCount());
        }
        assertTrue(Files.exists(part));
        assertFalse(Files.exists(ZipPartWriter.partialOf(part)));

        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        try (ZipFile zip = ZipFile.builder().setPath(ArchiveTestSupport.merge(dir, part)).get()) {
            assertEquals(List.of("srv/data/", "srv/data/notes.txt", "srv/data/movie.mkv", "srv/data/empty.txt"),
                Collections.list(zip.getEntriesInPhysicalOrder()).stream().map(ZipArchiveEntry::getName).toList());
            ZipArchiveEntry notes = zip.getEntry("srv/data/notes.txt");
            assertEquals(ZipEntry.DEFLATED, notes.getMethod());
            assertEquals(text.length(), notes.getSize());
            assertTrue(notes.getCompressedSize() < text.length() / 4);
            assertEquals(crc.getValue(), notes.getCrc());
            assertEquals(UnixStat.FILE_FLAG | 0640, notes.getUnixMode());
            assertEquals(1_700_000_001_000L, notes.getTime());
            assertEquals(1_700_000_001L, ((X5455_ExtendedTimestamp) notes.getExtraField(X5455_ExtendedTimestamp.HEADER_ID)).getModifyJavaTime().getTime() / 1000);
            assertEquals(text, new String(zip.getInputStream(notes).readAllBytes(), StandardCharsets.UTF_8));

            ZipArchiveEntry movie = zip.getEntry("srv/data/movie.mkv");
            assertEquals(ZipEntry.STORED, movie.getMethod());
            assertEquals(text.length(), movie.getCompressedSize());
            assertEquals(UnixStat.FILE_FLAG | 0400, movie.getUnixMode());
            assertEquals(1_700_000_003_000L, movie.getTime());
            assertEquals(text, new String(zip.getInputStream(movie).readAllBytes(), StandardCharsets.UTF_8));

            assertEquals(UnixStat.DIR_FLAG | 0755, zip.getEntry("srv/data/").getUnixMode());
            assertTrue(zip.getEntry("srv/data/").isDirectory());
            assertEquals("", new String(zip.getInputStream(zip.getEntry("srv/data/empty.txt")).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void writesOwnerOnlyInTheLocalHeaderAndTimesOutsideTheExtendedRangeAsDosOnly() throws Exception {
        Path part = dir.resolve("0.part");
        long before1980 = 100_000_000L;
        long after2038 = 4_300_000_000_000L;
        try (var writer = new ZipPartWriter(part, new ZipCompression(1))) {
            writer.addEntry("old", FILE, 1000, 1001, before1980, 1, bytes("x"));
            writer.addEntry("pre1970", FILE, 1000, 1001, -1_000_000_000_000L, 1, bytes("z"));
            writer.addEntry("future", FILE, 3000000000L, 1001, after2038, 1, bytes("y"));
            writer.complete();
        }

        try (ZipFile zip = ZipFile.builder().setPath(ArchiveTestSupport.merge(dir, part)).get()) {
            ZipArchiveEntry old = zip.getEntry("old");
            assertNotNull(old.getExtraField(X5455_ExtendedTimestamp.HEADER_ID));
            assertNull(zip.getEntry("pre1970").getExtraField(X5455_ExtendedTimestamp.HEADER_ID));
            ZipArchiveEntry future = zip.getEntry("future");
            assertNull(future.getExtraField(X5455_ExtendedTimestamp.HEADER_ID));
            assertTrue(future.getTime() > 0);
            X7875_NewUnix owner = (X7875_NewUnix) future.getExtraField(new X7875_NewUnix().getHeaderId());
            assertEquals(3000000000L, owner.getUID());
        }
        assumeTrue(ArchiveTestSupport.available("bsdtar"));
        String listing = ArchiveTestSupport.run(null, "bsdtar", "-tvf", ArchiveTestSupport.merge(dir, part).toString(), "--numeric-owner");
        assertTrue(listing.matches("(?s).*\\b3000000000\\s+1001\\b.*"), listing);
        assertTrue(listing.matches("(?s).*\\b1000\\s+1001\\b.*"), listing);
    }

    @Test
    void copiesHardLinkTargetsUnderTheLinkNameAndKeepsTheirMethod() throws Exception {
        Path part = dir.resolve("0.part");
        String text = "video".repeat(500);
        try (var writer = new ZipPartWriter(part, new ZipCompression(1))) {
            writer.addEntry("a/clip.mp4", FILE, 1, 2, 1_700_000_000_000L, text.length(), bytes(text));
            writer.addEntry("a/log.txt", FILE, 1, 2, 1_700_000_000_000L, text.length(), bytes(text));
            assertTrue(writer.addCopy("a/clip-link.mp4", "a/clip.mp4"));
            assertTrue(writer.addCopy("a/log-link.txt", "a/log.txt"));
            assertTrue(writer.addCopy("a/log-link-link.txt", "a/log-link.txt"));
            writer.complete();
            assertEquals(5, writer.entryCount());
        }

        Path archive = ArchiveTestSupport.merge(dir, part);
        assertEquals(text, ArchiveTestSupport.contents(archive).get("a/clip-link.mp4"));
        assertEquals(text, ArchiveTestSupport.contents(archive).get("a/log-link-link.txt"));
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            assertEquals(ZipEntry.STORED, zip.getEntry("a/clip-link.mp4").getMethod());
            assertEquals(ZipEntry.DEFLATED, zip.getEntry("a/log-link.txt").getMethod());
            assertEquals(zip.getEntry("a/log.txt").getCrc(), zip.getEntry("a/log-link.txt").getCrc());
        }
    }

    @Test
    void hardLinksToMissingTargetsFailButSkippedTargetsAreIgnored() throws Exception {
        try (var writer = new ZipPartWriter(dir.resolve("0.part"), new ZipCompression(1))) {
            add(writer, "a/one", "1");
            writer.markSkipped("a/pipe");

            assertFalse(writer.addCopy("a/pipe-link", "a/pipe"));
            assertThrows(IOException.class, () -> writer.addCopy("a/link", "a/missing"));
            assertTrue(writer.contains("a/one"));
            assertFalse(writer.contains("a/pipe-link"));
        }
    }

    @Test
    void anEntryShorterThanItsDeclaredSizeFailsAndClosingDiscardsTheIncompletePart() throws Exception {
        Path part = dir.resolve("0.part");
        try (var writer = new ZipPartWriter(part, new ZipCompression(1))) {
            assertThrows(EOFException.class, () -> writer.addEntry("a", FILE, 1, 1, 0, 10, bytes("short")));
        }

        assertFalse(Files.exists(part));
        assertFalse(Files.exists(ZipPartWriter.partialOf(part)));
        assertFalse(Files.exists(ZipPartWriter.indexOf(part)));
    }

    @Test
    void mergeFailsOnADuplicateNameAcrossPartsAndOnAReservedNameInAServicePart() throws Exception {
        Path first = dir.resolve("0.part");
        Path second = dir.resolve("1.part");
        Path snapshot = dir.resolve("snap.part");
        Path live = dir.resolve("live.part");
        for (Path part : List.of(first, second)) {
            try (var writer = new ZipPartWriter(part, new ZipCompression(1))) {
                add(writer, "a/same", "x");
                writer.complete();
            }
        }
        try (var writer = new ZipPartWriter(snapshot, new ZipCompression(1))) {
            add(writer, "db/mediagarrd.db", "snapshot");
            writer.complete();
        }
        try (var writer = new ZipPartWriter(live, new ZipCompression(1))) {
            add(writer, "db/mediagarrd.db-wal", "live");
            writer.complete();
        }

        Path out = dir.resolve("out.zip");
        assertThrows(IOException.class, () -> ZipPartMerger.merge(List.of(), List.of(first, second), out, dir));
        assertThrows(IOException.class, () -> ZipPartMerger.merge(List.of(snapshot), List.of(live), out, dir));
        assertFalse(Files.exists(dir.resolve("merge-names.idx")));
    }

    @Test
    void mergeChecksumMatchesTheWrittenFileAndOffsetsSurviveSeveralParts() throws Exception {
        Path first = dir.resolve("0.part");
        Path second = dir.resolve("1.part");
        for (Path part : List.of(first, second)) {
            try (var writer = new ZipPartWriter(part, new ZipCompression(1))) {
                add(writer, part.getFileName() + "/x", "data " + part.getFileName());
                add(writer, part.getFileName() + "/y.mp4", "stored " + part.getFileName());
                writer.complete();
            }
        }
        Path out = dir.resolve("out.zip");

        ZipPartMerger.Result result = ZipPartMerger.merge(List.of(), List.of(first, second), out, dir);

        assertEquals(4, result.entries());
        assertEquals(Files.size(out), result.bytes());
        assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(out))), result.checksum());
        assertEquals("stored 1.part", ArchiveTestSupport.contents(out).get("1.part/y.mp4"));
    }

    @Test
    @Timeout(120)
    void manyEntriesBeyondTheClassicLimitUseZip64AndReadBack() throws Exception {
        Path part = dir.resolve("0.part");
        int count = 70_000;
        try (var writer = new ZipPartWriter(part, new ZipCompression(1))) {
            for (int i = 0; i < count; i++) {
                add(writer, "many/f" + i, Integer.toString(i));
            }
            writer.complete();
        }
        Path archive = ArchiveTestSupport.merge(dir, part);

        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            assertEquals(count, Collections.list(zip.getEntries()).size());
            assertEquals("69999", new String(zip.getInputStream(zip.getEntry("many/f69999")).readAllBytes(), StandardCharsets.UTF_8));
        }
        verifyWithTools(archive, count);
    }

    @Test
    void smallArchiveIsAcceptedByExternalTools() throws Exception {
        Path part = dir.resolve("0.part");
        try (var writer = new ZipPartWriter(part, new ZipCompression(1))) {
            writer.addEntry("srv/", DIRECTORY, 0, 0, 1_700_000_001_000L, 0, bytes(""));
            add(writer, "srv/a.txt", "hello".repeat(100));
            add(writer, "srv/b.mp4", "world".repeat(100));
            assertTrue(writer.addCopy("srv/c.txt", "srv/a.txt"));
            writer.complete();
        }

        verifyWithTools(ArchiveTestSupport.merge(dir, part), 4);
    }

    static void verifyWithTools(Path archive, int entries) throws Exception {
        if (ArchiveTestSupport.available("unzip")) {
            String output = ArchiveTestSupport.run(null, "unzip", "-tq", archive.toString());
            assertTrue(output.contains("No errors"), output);
        }
        if (ArchiveTestSupport.available("bsdtar")) {
            assertEquals(entries, ArchiveTestSupport.runCounting(null, "bsdtar", "-tf", archive.toString()).lines());
            assertEquals(entries, ArchiveTestSupport.runCounting(archive, "bsdtar", "-tf", "-").lines());
        }
        if (ArchiveTestSupport.available("python3")) {
            ArchiveTestSupport.run(null, "python3", "-I", "-m", "zipfile", "-t", archive.toString());
        }
        if (ArchiveTestSupport.available("7z")) {
            String output = ArchiveTestSupport.run(null, "7z", "t", archive.toString());
            assertTrue(output.contains("Everything is Ok"), output);
        }
    }
}
