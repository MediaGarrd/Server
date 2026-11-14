package dry4java;

public record Candidate(double score, Location left, Location right, int leftNodes, int rightNodes) {
}
