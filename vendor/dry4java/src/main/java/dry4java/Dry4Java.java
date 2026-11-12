package dry4java;

import java.util.List;
import java.util.Locale;

public final class Dry4Java {
    public static final String USAGE = String.join(System.lineSeparator(),
            "Usage: dry4java [options] [file-or-directory ...]",
            "",
            "Options:",
            "  --threshold N   Minimum structural similarity score, default 0.82",
            "  --min-lines N   Minimum source lines in a candidate declaration, default 4",
            "  --min-nodes N   Minimum normalized syntax nodes, default 20",
            "  --format F      text or edn, default text",
            "  --edn           Same as --format edn",
            "  --text          Same as --format text");

    private Dry4Java() {
    }

    public static void main(String... args) {
        Options options = Options.parse(args);
        if (options.help()) {
            System.out.println(USAGE);
            return;
        }

        List<Candidate> candidates = new JavaDuplicateFinder().findDuplicates(options);
        switch (options.format()) {
            case "edn" -> System.out.println(toEdn(candidates));
            case "text" -> printText(candidates);
            default -> {
                System.err.println("Unknown format: " + options.format());
                System.exit(2);
            }
        }
    }

    static void printText(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            System.out.println("No duplicate candidates found.");
            return;
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                System.out.println();
            }
            System.out.println(formatCandidate(candidates.get(i)));
        }
    }

    static String formatCandidate(Candidate candidate) {
        return "DUPLICATE score=" + String.format(Locale.US, "%.2f", candidate.score()) + "\n"
                + "  " + lineRange(candidate.left()) + "\n"
                + "  " + lineRange(candidate.right());
    }

    static String toEdn(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return "{:candidates []}";
        }
        StringBuilder builder = new StringBuilder("{:candidates\n [");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                builder.append("\n  ");
            }
            Candidate candidate = candidates.get(i);
            builder.append("{:score ").append(candidate.score())
                    .append("\n   :left ").append(locationEdn(candidate.left()))
                    .append("\n   :right ").append(locationEdn(candidate.right()))
                    .append("\n   :left-nodes ").append(candidate.leftNodes())
                    .append("\n   :right-nodes ").append(candidate.rightNodes())
                    .append("}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private static String locationEdn(Location location) {
        return "{:file \"" + escape(location.file()) + "\", :start-line " + location.startLine()
                + ", :end-line " + location.endLine() + "}";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String lineRange(Location location) {
        return location.file() + ":" + location.startLine() + "-" + location.endLine();
    }
}
