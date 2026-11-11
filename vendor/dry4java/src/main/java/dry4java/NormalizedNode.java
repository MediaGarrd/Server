package dry4java;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

final class NormalizedNode {
    private final String tag;
    private final List<NormalizedNode> children;

    NormalizedNode(String tag, List<NormalizedNode> children) {
        this.tag = tag;
        this.children = List.copyOf(children);
    }

    int nodeCount() {
        int count = 1;
        for (NormalizedNode child : children) {
            count += child.nodeCount();
        }
        return count;
    }

    Set<String> fingerprints() {
        Set<String> result = new TreeSet<>();
        collectFingerprints(result);
        return result;
    }

    private void collectFingerprints(Set<String> result) {
        result.add(toFingerprint());
        for (NormalizedNode child : children) {
            child.collectFingerprints(result);
        }
    }

    private String toFingerprint() {
        if (children.isEmpty()) {
            return tag;
        }
        List<String> parts = new ArrayList<>();
        parts.add(tag);
        for (NormalizedNode child : children) {
            parts.add(child.toFingerprint());
        }
        return "(" + String.join(" ", parts) + ")";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof NormalizedNode that)) {
            return false;
        }
        return Objects.equals(tag, that.tag) && Objects.equals(children, that.children);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tag, children);
    }
}
