package wellatleastitried.mediagarrdServer.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record ServiceDefinition(String name, List<String> paths) {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    public ServiceDefinition {
        name = name == null ? "" : name.strip();
        paths = paths == null ? List.of() : paths.stream()
            .filter(Objects::nonNull)
            .map(String::strip)
            .filter(path -> !path.isEmpty())
            .map(path -> path.startsWith("/") ? Path.of(path).normalize().toString() : path)
            .distinct()
            .toList();
    }

    public ServiceDefinition validate() {
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "Service name must be 1-64 characters of letters, digits, '.', '_' or '-' and start with a letter or digit");
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("Service '" + name + "' needs at least one path");
        }
        for (String path : paths) {
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("Path must be absolute: " + path);
            }
            if (path.equals("/")) {
                throw new IllegalArgumentException("Path must not be the filesystem root");
            }
            if (path.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Path must not contain control characters: " + path);
            }
        }
        return this;
    }
}
