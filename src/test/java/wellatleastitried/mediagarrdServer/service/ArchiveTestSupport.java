package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

final class ArchiveTestSupport {

    private ArchiveTestSupport() {}

    static Path merge(Path scratch, Path... parts) throws IOException {
        return merge(scratch, List.of(), List.of(parts));
    }

    static Path merge(Path scratch, List<Path> snapshotParts, List<Path> parts) throws IOException {
        Path archive = scratch.resolve("merged-" + System.nanoTime() + ".zip");
        ZipPartMerger.merge(snapshotParts, parts, archive, scratch);
        return archive;
    }

    static Map<String, String> contents(Path zipPath) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (ZipFile zip = ZipFile.builder().setPath(zipPath).get()) {
            for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return entries;
    }

    static Map<String, String> partContents(Path scratch, Path... parts) throws IOException {
        return contents(merge(scratch, parts));
    }

    static boolean available(String tool) {
        try {
            Process process = new ProcessBuilder(tool, "--help").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            process.waitFor(20, TimeUnit.SECONDS);
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    record Output(String text, long lines) {}

    static String run(Path stdin, String... command) throws IOException, InterruptedException {
        return runCounting(stdin, command).text();
    }

    static Output runCounting(Path stdin, String... command) throws IOException, InterruptedException {
        Path output = Files.createTempFile("tool-output", ".txt");
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
            if (stdin != null) {
                builder.redirectInput(stdin.toFile());
            }
            Process process = builder.start();
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("Timed out: " + String.join(" ", command));
            }
            String text = head(output);
            assertEquals(0, process.exitValue(), String.join(" ", command) + "\n" + text);
            try (var lines = Files.lines(output, StandardCharsets.ISO_8859_1)) {
                return new Output(text, lines.count());
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static String head(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(1_000_000), StandardCharsets.UTF_8);
        }
    }
}
