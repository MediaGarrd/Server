package dry4java;

import java.util.ArrayList;
import java.util.List;

public final class Options {
    private final List<String> paths;
    private final double threshold;
    private final int minLines;
    private final int minNodes;
    private final String format;
    private final boolean help;

    public Options(List<String> paths, double threshold, int minLines, int minNodes, String format, boolean help) {
        this.paths = List.copyOf(paths);
        this.threshold = threshold;
        this.minLines = minLines;
        this.minNodes = minNodes;
        this.format = format;
        this.help = help;
    }

    public static Options defaults() {
        return new Options(List.of("src"), 0.82, 4, 20, "text", false);
    }

    public static Options parse(String... args) {
        List<String> paths = new ArrayList<>();
        double threshold = 0.82;
        int minLines = 4;
        int minNodes = 20;
        String format = "text";
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--threshold" -> threshold = Double.parseDouble(valueFor(args, ++i, arg));
                case "--min-lines" -> minLines = Integer.parseInt(valueFor(args, ++i, arg));
                case "--min-nodes" -> minNodes = Integer.parseInt(valueFor(args, ++i, arg));
                case "--format" -> format = valueFor(args, ++i, arg);
                case "--edn" -> format = "edn";
                case "--text" -> format = "text";
                case "--help", "-h" -> help = true;
                default -> paths.add(arg);
            }
        }

        if (paths.isEmpty()) {
            paths.add("src");
        }
        return new Options(paths, threshold, minLines, minNodes, format, help);
    }

    private static String valueFor(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    public List<String> paths() {
        return paths;
    }

    public double threshold() {
        return threshold;
    }

    public int minLines() {
        return minLines;
    }

    public int minNodes() {
        return minNodes;
    }

    public String format() {
        return format;
    }

    public boolean help() {
        return help;
    }
}
