package dry4java;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class JavaDuplicateFinder {
    static {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    private final JavaNormalizer normalizer = new JavaNormalizer();

    public List<Candidate> findDuplicates() {
        return findDuplicates(Options.defaults());
    }

    public List<Candidate> findDuplicates(Options options) {
        List<Entry> entries = scan(options.paths()).stream()
                .filter(entry -> entry.lines() >= options.minLines())
                .filter(entry -> entry.nodes >= options.minNodes())
                .toList();
        List<Candidate> candidates = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                Entry left = entries.get(i);
                Entry right = entries.get(j);
                double score = similarity(left, right);
                if (!left.overlaps(right) && score >= options.threshold()) {
                    candidates.add(new Candidate(score, left.location(), right.location(), left.nodes, right.nodes));
                }
            }
        }

        candidates.sort(Comparator
                .comparingDouble(Candidate::score).reversed()
                .thenComparing(candidate -> candidate.left().file())
                .thenComparingInt(candidate -> candidate.left().startLine())
                .thenComparing(candidate -> candidate.right().file())
                .thenComparingInt(candidate -> candidate.right().startLine()));
        return candidates;
    }

    private List<Entry> scan(List<String> paths) {
        return paths.stream()
                .flatMap(path -> javaFiles(Path.of(path)).stream())
                .sorted()
                .flatMap(path -> scanFile(path).stream())
                .toList();
    }

    private List<Path> javaFiles(Path path) {
        if (Files.isRegularFile(path) && path.toString().endsWith(".java")) {
            return List.of(path);
        }
        if (!Files.isDirectory(path)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to scan " + path, e);
        }
    }

    private List<Entry> scanFile(Path file) {
        try {
            CompilationUnit unit = StaticJavaParser.parse(file);
            List<Entry> entries = new ArrayList<>();
            collectEntries(file, unit, entries);
            return entries;
        } catch (IOException | ParseProblemException e) {
            throw new IllegalStateException("Unable to parse " + file, e);
        }
    }

    private void collectEntries(Path file, Node node, List<Entry> entries) {
        if (isCandidateRoot(node)) {
            entries.add(entry(file, node));
        }
        for (Node child : node.getChildNodes()) {
            collectEntries(file, child, entries);
        }
    }

    private boolean isCandidateRoot(Node node) {
        return node instanceof ClassOrInterfaceDeclaration
                || node instanceof RecordDeclaration
                || node instanceof EnumDeclaration
                || node instanceof AnnotationDeclaration
                || node instanceof MethodDeclaration
                || node instanceof ConstructorDeclaration
                || node instanceof FieldDeclaration
                || node instanceof InitializerDeclaration
                || node instanceof EnumConstantDeclaration
                || node instanceof LambdaExpr;
    }

    private Entry entry(Path file, Node node) {
        int startLine = node.getRange().map(range -> range.begin.line).orElse(1);
        int endLine = node.getRange().map(range -> range.end.line).orElse(startLine);
        NormalizedNode normalized = normalizer.normalize(node);
        return new Entry(
                file.toString(),
                startLine,
                endLine,
                normalized.nodeCount(),
                normalized.fingerprints());
    }

    private double similarity(Entry left, Entry right) {
        Set<String> intersection = new HashSet<>(left.fingerprints);
        intersection.retainAll(right.fingerprints);
        Set<String> union = new HashSet<>(left.fingerprints);
        union.addAll(right.fingerprints);
        if (union.isEmpty()) {
            return 0.0;
        }
        return (double) intersection.size() / union.size();
    }

    private record Entry(String file, int startLine, int endLine, int nodes, Set<String> fingerprints) {
        int lines() {
            return endLine - startLine + 1;
        }

        Location location() {
            return new Location(file, startLine, endLine);
        }

        boolean overlaps(Entry other) {
            return file.equals(other.file)
                    && startLine <= other.endLine
                    && other.startLine <= endLine;
        }
    }
}
