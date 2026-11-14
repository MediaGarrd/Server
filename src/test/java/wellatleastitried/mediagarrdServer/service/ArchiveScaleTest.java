package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import wellatleastitried.mediagarrdServer.service.GeneratedTar.Spec;

@Tag("scale")
@Timeout(value = 60, unit = java.util.concurrent.TimeUnit.MINUTES)
class ArchiveScaleTest {

    private static final long FOUR_GIB = 0x100000000L;
    private static final byte[] SMALL = "small file content\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private Thread sampler;
    private volatile boolean sampling;
    private volatile long peakHeap;
    private long started;

    @BeforeEach
    void startSampling() {
        assumeTrue(Runtime.getRuntime().maxMemory() <= 128L * 1024 * 1024, "run through scaleTest, which caps the heap");
        started = System.nanoTime();
        sampling = true;
        sampler = new Thread(() -> {
            while (sampling) {
                Runtime runtime = Runtime.getRuntime();
                peakHeap = Math.max(peakHeap, runtime.totalMemory() - runtime.freeMemory());
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "heap-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    @AfterEach
    void report(org.junit.jupiter.api.TestInfo info) throws Exception {
        sampling = false;
        sampler.join();
        System.out.printf("SCALE %s: %d s, max heap %d MiB, peak sampled used heap %d MiB, tmpdir %s%n", info.getDisplayName(),
            (System.nanoTime() - started) / 1_000_000_000L, Runtime.getRuntime().maxMemory() >> 20, peakHeap >> 20, System.getProperty("java.io.tmpdir"));
    }

    private static byte[] randomBlock() {
        byte[] block = new byte[64 * 1024];
        new Random(42).nextBytes(block);
        return block;
    }

    private Path archive(Iterator<Spec> specs, String hostPath, int level) throws Exception {
        Path part = dir.resolve("0.part");
        try (var tar = new GeneratedTar(specs)) {
            ServiceBackupRunner.archive(tar, hostPath, part, new ZipCompression(level));
        }
        System.out.printf("SCALE part %d MiB%n", Files.size(part) >> 20);
        Path merged = dir.resolve("merged.zip");
        ZipPartMerger.Result result = ZipPartMerger.merge(List.of(), List.of(part), merged, dir);
        Files.delete(part);
        Files.delete(ZipPartWriter.indexOf(part));
        System.out.printf("SCALE archive %d MiB, %d entries, sha256 %s%n", Files.size(merged) >> 20, result.entries(), result.checksum());
        return merged;
    }

    @Test
    void threeHundredThousandSmallFilesBuildAnArchiveReadableByEveryTool() throws Exception {
        int count = 300_000;
        Iterator<Spec> specs = java.util.stream.Stream.concat(
            java.util.stream.Stream.of(Spec.directory("many/")),
            IntStream.range(0, count).mapToObj(i -> Spec.file("many/dir" + (i % 100) + "/file-" + i + ".txt", SMALL.length, SMALL))).iterator();

        Path merged = archive(specs, "/srv/many", 1);

        ZipPartWriterTest.verifyWithTools(merged, count + 1);
    }

    @Test
    void aStoredEntryJustUnderFourGiBUsesALocalZip64ExtraWithClassicCentralSizes() throws Exception {
        long size = 0xF0000000L + 0x100000L;

        Path merged = archive(List.of(Spec.file("movies/film.mkv", size, randomBlock())).iterator(), "/srv/movies", 1);

        assertTrue(size < 0xFFFFFFFFL);
        ZipPartWriterTest.verifyWithTools(merged, 1);
    }

    @Test
    void aStoredEntryOverFourGiBFollowedBySmallEntriesPushesOffsetsPastFourGiB() throws Exception {
        long size = FOUR_GIB + 300_000_000L;
        int small = 1000;
        Iterator<Spec> specs = java.util.stream.Stream.concat(
            java.util.stream.Stream.of(Spec.file("movies/big.mkv", size, randomBlock())),
            IntStream.range(0, small).mapToObj(i -> Spec.file("movies/small-" + i + ".txt", SMALL.length, SMALL))).iterator();

        Path merged = archive(specs, "/srv/movies", 1);

        assertTrue(Files.size(merged) > FOUR_GIB);
        ZipPartWriterTest.verifyWithTools(merged, small + 1);
        extractOne(merged, "srv/movies/small-999.txt", new String(SMALL, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void aDeflatedEntryOverFourGiBOfRepeatedBytesOverflowsOnlyTheUncompressedSize() throws Exception {
        long size = FOUR_GIB + 200_000_000L;

        Path merged = archive(List.of(Spec.file("logs/huge.log", size, new byte[64 * 1024])).iterator(), "/srv/logs", 1);

        assertTrue(Files.size(merged) < FOUR_GIB);
        ZipPartWriterTest.verifyWithTools(merged, 1);
    }

    private void extractOne(Path archive, String name, String expected) throws IOException, InterruptedException {
        if (ArchiveTestSupport.available("bsdtar")) {
            assertEquals(expected, ArchiveTestSupport.run(null, "bsdtar", "-xOf", archive.toString(), name));
            assertEquals(expected, ArchiveTestSupport.run(archive, "bsdtar", "-xOf", "-", name));
        }
        if (ArchiveTestSupport.available("unzip")) {
            assertEquals(expected, ArchiveTestSupport.run(null, "unzip", "-p", archive.toString(), name));
        }
    }
}
