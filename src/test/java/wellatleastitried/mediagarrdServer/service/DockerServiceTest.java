package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;

class DockerServiceTest {

    private static final String IMAGE = "busybox:stable";
    private static final ServiceDefinition RADARR = new ServiceDefinition("radarr", List.of("/srv/radarr/config", "/srv/radarr/data"));

    private final DockerClient client = mock(DockerClient.class);
    private final ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
    private final CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
    private final RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, RETURNS_SELF);
    private final InspectImageCmd inspectCmd = mock(InspectImageCmd.class);
    private DockerService docker;

    @BeforeEach
    void setUp() {
        MediaGarrdProperties properties = new MediaGarrdProperties();
        properties.setHelperImage(IMAGE);
        docker = new DockerService(client, properties);

        when(client.listContainersCmd()).thenReturn(listCmd);
        when(client.createContainerCmd(IMAGE)).thenReturn(createCmd);
        when(client.removeContainerCmd(anyString())).thenReturn(removeCmd);
        when(client.inspectImageCmd(IMAGE)).thenReturn(inspectCmd);
        CreateContainerResponse created = new CreateContainerResponse();
        created.setId("new-id");
        when(createCmd.exec()).thenReturn(created);
    }

    @Test
    void createsReadOnlyNetworklessHelperContainer() {
        when(listCmd.exec()).thenReturn(List.of());

        assertEquals("new-id", docker.containerFor(RADARR));

        verify(listCmd).withLabelFilter(Map.of(DockerService.SERVICE_LABEL, "radarr"));
        verify(createCmd).withName("mediagarrd-backup-radarr");
        verify(createCmd).withLabels(Map.of(
            DockerService.SERVICE_LABEL, "radarr",
            DockerService.CONFIG_HASH_LABEL, docker.configHash(RADARR)));
        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        assertEquals("none", hostConfig.getValue().getNetworkMode());
        assertEquals(
            List.of(bind("/srv/radarr/config"), bind("/srv/radarr/data")),
            hostConfig.getValue().getMounts().stream().map(DockerServiceTest::describe).toList());
        verify(client, never()).startContainerCmd(anyString());
    }

    @Test
    void reusesContainerWithMatchingConfigHash() {
        Container existing = container("existing", docker.configHash(RADARR));
        when(listCmd.exec()).thenReturn(List.of(existing));

        assertEquals("existing", docker.containerFor(RADARR));

        verify(client, never()).createContainerCmd(anyString());
        verify(client, never()).removeContainerCmd(anyString());
    }

    @Test
    void replacesContainerWhenConfigChanged() {
        Container stale = container("stale", "old-hash");
        when(listCmd.exec()).thenReturn(List.of(stale));

        assertEquals("new-id", docker.containerFor(RADARR));

        verify(client).removeContainerCmd("stale");
        verify(removeCmd).withForce(true);
    }

    @Test
    void pullsHelperImageOnlyWhenMissing() throws Exception {
        when(listCmd.exec()).thenReturn(List.of());
        when(inspectCmd.exec()).thenThrow(new NotFoundException("missing"));
        PullImageCmd pullCmd = mock(PullImageCmd.class);
        PullImageResultCallback callback = mock(PullImageResultCallback.class);
        when(client.pullImageCmd(IMAGE)).thenReturn(pullCmd);
        when(pullCmd.start()).thenReturn(callback);
        when(callback.awaitCompletion(anyLong(), any(TimeUnit.class))).thenReturn(true);

        docker.containerFor(RADARR);

        verify(client).pullImageCmd(IMAGE);
    }

    @Test
    void failsWhenHelperImagePullTimesOut() throws Exception {
        when(listCmd.exec()).thenReturn(List.of());
        when(inspectCmd.exec()).thenThrow(new NotFoundException("missing"));
        PullImageCmd pullCmd = mock(PullImageCmd.class);
        PullImageResultCallback callback = mock(PullImageResultCallback.class);
        when(client.pullImageCmd(IMAGE)).thenReturn(pullCmd);
        when(pullCmd.start()).thenReturn(callback);
        when(callback.awaitCompletion(anyLong(), any(TimeUnit.class))).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> docker.containerFor(RADARR));
        verify(client, never()).createContainerCmd(anyString());
    }

    @Test
    void removeContainersNeverThrows() {
        List<Container> containers = List.of(container("a", "x"), container("b", "y"));
        when(listCmd.exec()).thenReturn(containers);
        when(removeCmd.exec()).thenThrow(new NotFoundException("gone")).thenThrow(new RuntimeException("daemon down"));

        docker.removeContainers("radarr");

        verify(client).removeContainerCmd("a");
        verify(client).removeContainerCmd("b");
    }

    @Test
    void configHashDependsOnPathsNotTheirOrder() {
        ServiceDefinition reordered = new ServiceDefinition("other", List.of("/srv/radarr/data", "/srv/radarr/config"));
        ServiceDefinition changed = new ServiceDefinition("radarr", List.of("/srv/radarr/config"));

        assertEquals(docker.configHash(RADARR), docker.configHash(reordered));
        assertNotEquals(docker.configHash(RADARR), docker.configHash(changed));
    }

    @Test
    void mapsHostPathsUnderMountRoot() {
        assertEquals("/mnt/srv/radarr/config", DockerService.containerPath("/srv/radarr/config"));
    }

    private static Container container(String id, String hash) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(Map.of(DockerService.SERVICE_LABEL, "radarr", DockerService.CONFIG_HASH_LABEL, hash));
        return container;
    }

    private static String bind(String hostPath) {
        return MountType.BIND + " " + hostPath + " -> /mnt" + hostPath + " ro=true";
    }

    private static String describe(Mount mount) {
        return mount.getType() + " " + mount.getSource() + " -> " + mount.getTarget() + " ro=" + mount.getReadOnly();
    }
}
