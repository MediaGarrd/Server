package wellatleastitried.mediagarrdServer.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ContainerEnvironment {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerEnvironment.class);

    private static final Pattern CONTAINER_ID = Pattern.compile("containers/([0-9a-f]{64})/");
    private static final Set<String> CONTAINER_OWNED_FILES = Set.of("/etc/hostname", "/etc/resolv.conf", "/etc/hosts");

    private final Path mountInfo;
    private final List<Path> markers;
    private final Supplier<String> hostname;

    @Autowired
    public ContainerEnvironment() {
        this(Path.of("/proc/self/mountinfo"), List.of(Path.of("/.dockerenv"), Path.of("/run/.containerenv")), () -> System.getenv("HOSTNAME"));
    }

    ContainerEnvironment(Path mountInfo, List<Path> markers, Supplier<String> hostname) {
        this.mountInfo = mountInfo;
        this.markers = markers;
        this.hostname = hostname;
    }

    public boolean insideContainer() {
        return markers.stream().anyMatch(Files::exists) || mountedContainerId().isPresent();
    }

    public List<String> containerIdCandidates() {
        List<String> candidates = new ArrayList<>();
        Optional<String> mounted = mountedContainerId();
        if (mounted.isPresent()) {
            return List.of(mounted.get());
        }
        String name = hostname.get();
        if (name != null && !name.isBlank() && !candidates.contains(name.strip())) {
            candidates.add(name.strip());
        }
        return candidates;
    }

    private Optional<String> mountedContainerId() {
        try {
            for (String line : Files.readAllLines(mountInfo)) {
                String[] fields = line.split(" ");
                if (fields.length > 4 && CONTAINER_OWNED_FILES.contains(fields[4])) {
                    Matcher matcher = CONTAINER_ID.matcher(line);
                    if (matcher.find()) {
                        return Optional.of(matcher.group(1));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Unable to read {}", mountInfo, e);
        }
        return Optional.empty();
    }
}
