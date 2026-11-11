package dry4java;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDuplicateFinderTest {
    @Test
    void reportsStructuralDuplicateCandidatesWithFileAndLineRanges() throws Exception {
        Path dir = Files.createTempDirectory("dry4java");
        Path left = writeSource(dir, "Left.java", """
                package sample;

                class Left {
                    int alpha(List<Integer> xs) {
                        var ys = xs.stream().filter(x -> x % 2 == 1).toList();
                        return ys.stream().mapToInt(x -> x + 1).sum();
                    }
                }
                """);
        Path right = writeSource(dir, "Right.java", """
                package sample;

                class Right {
                    int beta(List<Integer> items) {
                        var kept = items.stream().filter(item -> item % 2 == 0).toList();
                        return kept.stream().mapToInt(item -> item - 1).sum();
                    }
                }
                """);

        List<Candidate> candidates = new JavaDuplicateFinder().findDuplicates(
                new Options(List.of(dir.toString()), 0.50, 3, 8, "text", false));

        Candidate candidate = candidates.stream()
                .filter(each -> each.left().file().equals(left.toString()))
                .filter(each -> each.right().file().equals(right.toString()))
                .filter(each -> each.left().startLine() == 4)
                .filter(each -> each.right().startLine() == 4)
                .findFirst()
                .orElseThrow();
        assertEquals(4, candidate.left().startLine());
        assertEquals(7, candidate.left().endLine());
        assertEquals(4, candidate.right().startLine());
        assertEquals(7, candidate.right().endLine());
    }

    @Test
    void matchesRecordsWithDifferentNamesAndLiteralValues() throws Exception {
        Path dir = Files.createTempDirectory("dry4java");
        writeSource(dir, "One.java", """
                record Invoice(String id, int amount) {
                    boolean payable() {
                        return id != null && amount > 0;
                    }
                }
                """);
        writeSource(dir, "Two.java", """
                record Receipt(String code, int total) {
                    boolean closed() {
                        return code != null && total > 10;
                    }
                }
                """);

        List<Candidate> candidates = new JavaDuplicateFinder().findDuplicates(
                new Options(List.of(dir.toString()), 0.80, 3, 8, "text", false));

        assertTrue(candidates.stream().anyMatch(candidate ->
                candidate.left().file().endsWith("One.java")
                        && candidate.right().file().endsWith("Two.java")));
    }

    @Test
    void matchesEnumsAndConstantsStructurally() throws Exception {
        Path dir = Files.createTempDirectory("dry4java");
        writeSource(dir, "One.java", """
                enum One {
                    READY(1), DONE(2);
                    private final int code;
                    One(int code) {
                        this.code = code;
                    }
                }
                """);
        writeSource(dir, "Two.java", """
                enum Two {
                    OPEN(10), CLOSED(20);
                    private final int value;
                    Two(int value) {
                        this.value = value;
                    }
                }
                """);

        List<Candidate> candidates = new JavaDuplicateFinder().findDuplicates(
                new Options(List.of(dir.toString()), 0.80, 3, 8, "text", false));

        assertTrue(candidates.stream().anyMatch(candidate ->
                candidate.left().file().endsWith("One.java")
                        && candidate.right().file().endsWith("Two.java")));
    }

    @Test
    void filtersCandidatesShorterThanTheMinimumLineCount() throws Exception {
        Path dir = Files.createTempDirectory("dry4java");
        writeSource(dir, "One.java", "class One { int a(int x) { return x + 1; } }\n");
        writeSource(dir, "Two.java", "class Two { int b(int y) { return y + 2; } }\n");

        List<Candidate> candidates = new JavaDuplicateFinder().findDuplicates(
                new Options(List.of(dir.toString()), 0.80, 3, 1, "text", false));

        assertEquals(List.of(), candidates);
    }

    @Test
    void parsesCommandLineOptionsAndPaths() {
        Options options = Options.parse("--threshold", "0.9",
                "--min-lines", "5",
                "--min-nodes", "30",
                "--edn",
                "spec");

        assertEquals(List.of("spec"), options.paths());
        assertEquals(0.9, options.threshold());
        assertEquals(5, options.minLines());
        assertEquals(30, options.minNodes());
        assertEquals("edn", options.format());
    }

    @Test
    void defaultsToSrcWhenNoPathsAreProvided() {
        assertEquals(List.of("src"), Options.parse().paths());
    }

    @Test
    void formatsTextOutputWithLineRanges() {
        Candidate candidate = new Candidate(
                0.875,
                new Location("a.java", 10, 14),
                new Location("b.java", 20, 24),
                88,
                91);

        assertEquals("DUPLICATE score=0.88\n  a.java:10-14\n  b.java:20-24",
                Dry4Java.formatCandidate(candidate));
    }

    @Test
    void printsClearMessageWhenNoTextCandidatesExist() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(bytes));
        try {
            Dry4Java.printText(List.of());
        } finally {
            System.setOut(original);
        }

        assertEquals("No duplicate candidates found.\n", bytes.toString());
    }

    @Test
    void printsEdn() {
        assertEquals("{:candidates []}", Dry4Java.toEdn(List.of()));
    }

    private Path writeSource(Path dir, String name, String text) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }
}
