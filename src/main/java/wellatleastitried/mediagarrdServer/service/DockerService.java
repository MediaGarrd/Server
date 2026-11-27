package wellatleastitried.mediagarrdServer.service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;
import wellatleastitried.mediagarrdServer.utilities.Sha256;

@Service
public class DockerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerService.class);

    static final String SERVICE_LABEL = "mediagarrd.service";
    static final String CONFIG_HASH_LABEL = "mediagarrd.config-hash";
    static final String MOUNT_ROOT = "/mnt";
    private static final long IMAGE_PULL_TIMEOUT_MINUTES = 10;

    private final DockerClient client;
    private final String helperImage;

    public DockerService(DockerClient client, MediaGarrdProperties properties) {
        this.client = client;
        this.helperImage = properties.getHelperImage();
    }

    public String containerFor(ServiceDefinition service) {
        String hash = configHash(service);
        String reusable = null;
        for (Container container : managedContainers(service.name())) {
            if (reusable == null && hash.equals(container.getLabels().get(CONFIG_HASH_LABEL))) {
                reusable = container.getId();
                LOGGER.debug("[{}] Reusing helper container {}", service.name(), reusable);
                continue;
            }
            LOGGER.info("[{}] Removing stale helper container {}", service.name(), container.getId());
            removeContainer(container.getId());
        }
        return reusable != null ? reusable : createContainer(service, hash);
    }

    public InputStream copyFrom(String containerId, String hostPath) {
        return client.copyArchiveFromContainerCmd(containerId, containerPath(hostPath)).exec();
    }

    public List<InspectContainerResponse.Mount> mountsOf(String containerId) {
        List<InspectContainerResponse.Mount> mounts = client.inspectContainerCmd(containerId).exec().getMounts();
        return mounts == null ? List.of() : mounts;
    }

    public void removeContainers(String serviceName) {
        try {
            managedContainers(serviceName).forEach(container -> removeContainer(container.getId()));
        } catch (RuntimeException e) {
            LOGGER.warn("[{}] Unable to remove helper containers", serviceName, e);
        }
    }

    static String containerPath(String hostPath) {
        return MOUNT_ROOT + hostPath;
    }

    String configHash(ServiceDefinition service) {
        String fingerprint = helperImage + "\n" + String.join("\n", service.paths().stream().sorted().toList());
        return Sha256.hex(fingerprint);
    }

    private List<Container> managedContainers(String serviceName) {
        return client.listContainersCmd()
            .withShowAll(true)
            .withLabelFilter(Map.of(SERVICE_LABEL, serviceName))
            .exec();
    }

    private String createContainer(ServiceDefinition service, String hash) {
        ensureHelperImage();
        List<Mount> mounts = service.paths().stream()
            .map(path -> new Mount()
                .withType(MountType.BIND)
                .withSource(path)
                .withTarget(containerPath(path))
                .withReadOnly(true))
            .toList();
        String id = client.createContainerCmd(helperImage)
            .withName("mediagarrd-backup-" + service.name())
            .withLabels(Map.of(SERVICE_LABEL, service.name(), CONFIG_HASH_LABEL, hash))
            .withHostConfig(HostConfig.newHostConfig().withMounts(mounts).withNetworkMode("none"))
            .exec()
            .getId();
        LOGGER.info("[{}] Created helper container {} with {} mount(s)", service.name(), id, mounts.size());
        return id;
    }

    private synchronized void ensureHelperImage() {
        try {
            client.inspectImageCmd(helperImage).exec();
        } catch (NotFoundException e) {
            LOGGER.info("Pulling helper image {}", helperImage);
            try {
                if (!client.pullImageCmd(helperImage).start().awaitCompletion(IMAGE_PULL_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    throw new IllegalStateException("Timed out pulling helper image " + helperImage);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while pulling helper image " + helperImage, ex);
            }
        }
    }

    private void removeContainer(String containerId) {
        try {
            client.removeContainerCmd(containerId).withForce(true).exec();
        } catch (NotFoundException e) {
            LOGGER.debug("Helper container {} already removed", containerId);
        }
    }
}
