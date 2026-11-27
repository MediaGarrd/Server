package wellatleastitried.mediagarrdServer.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.dockerjava.api.command.InspectContainerResponse;

@Component
public class DatabaseHostPathResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseHostPathResolver.class);

    private final DockerService docker;
    private final ContainerEnvironment environment;

    public DatabaseHostPathResolver(DockerService docker, ContainerEnvironment environment) {
        this.docker = docker;
        this.environment = environment;
    }

    public Path resolve(Path databaseFile) throws IOException {
        Path real = databaseFile.toRealPath();
        if (!environment.insideContainer()) {
            return real;
        }

        List<String> candidates = environment.containerIdCandidates();
        if (candidates.isEmpty()) {
            throw new IOException("The server is running in a container but its container ID could not be determined");
        }

        List<String> failures = new ArrayList<>();
        for (String containerId : candidates) {
            try {
                Path hostPath = mapThroughMounts(real, docker.mountsOf(containerId));
                if (hostPath != null) {
                    return hostPath;
                }
                failures.add(containerId + ": no mount covers " + real);
            } catch (IOException e) {
                failures.add(containerId + ": " + e.getMessage());
            } catch (RuntimeException e) {
                LOGGER.debug("Unable to inspect container {}", containerId, e);
                failures.add(containerId + ": " + e.getMessage());
            }
        }
        throw new IOException("Unable to resolve the host path of " + real + " (" + String.join("; ", failures) + ")");
    }

    private static Path mapThroughMounts(Path real, List<InspectContainerResponse.Mount> mounts) throws IOException {
        InspectContainerResponse.Mount best = null;
        Path bestDestination = null;
        for (InspectContainerResponse.Mount mount : mounts) {
            if (mount.getDestination() == null || mount.getDestination().getPath() == null) {
                continue;
            }
            Path destination = Path.of(mount.getDestination().getPath()).normalize();
            if (real.startsWith(destination)
                && (bestDestination == null || destination.getNameCount() > bestDestination.getNameCount())) {
                best = mount;
                bestDestination = destination;
            }
        }
        if (best == null) {
            return null;
        }
        String source = best.getSource();
        if (source == null || source.isBlank()) {
            throw new IOException("the mount at " + bestDestination + " has no host source");
        }
        Path sourcePath = Path.of(source);
        Path hostPath = sourcePath.resolve(bestDestination.relativize(real).toString()).normalize();
        if (!sourcePath.isAbsolute() || hostPath.equals(hostPath.getRoot()) || !hostPath.startsWith(sourcePath.normalize())) {
            throw new IOException("the mount source " + source + " does not give a usable host path");
        }
        return hostPath;
    }
}
