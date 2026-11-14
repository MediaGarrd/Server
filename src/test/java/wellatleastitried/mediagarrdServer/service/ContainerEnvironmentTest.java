package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerEnvironmentTest {

    private static final String ID = "0123456789abcdef".repeat(4);

    @TempDir
    Path tempDir;

    @Test
    void findsTheContainerIdFromTheHostnameMountEvenWhenHostnameIsWrong() throws Exception {
        ContainerEnvironment environment = environment(mountInfo(
            "1 2 0:1 / / rw - overlay overlay rw",
            "3 1 8:1 /var/lib/docker/containers/" + ID + "/resolv.conf /etc/resolv.conf rw - ext4 /dev/sda1 rw",
            "4 1 8:1 /var/lib/docker/containers/" + ID + "/hostname /etc/hostname rw - ext4 /dev/sda1 rw"), "my-host");

        assertTrue(environment.insideContainer());
        assertEquals(List.of(ID), environment.containerIdCandidates());
    }

    @Test
    void neverFallsBackToTheHostnameWhenMountinfoGivesAnId() throws Exception {
        ContainerEnvironment environment = environment(mountInfo(
            "4 1 8:1 /var/lib/docker/containers/" + ID + "/hostname /etc/hostname rw - ext4 /dev/sda1 rw"), "other-container");

        assertEquals(List.of(ID), environment.containerIdCandidates());
    }

    @Test
    void findsRootlessDockerContainers() throws Exception {
        String line = "4 1 8:1 /home/u/.local/share/docker/containers/" + ID + "/hostname /etc/hostname rw - ext4 /dev/sda1 rw";

        assertEquals(List.of(ID), environment(mountInfo(line), null).containerIdCandidates());
    }

    @Test
    void ignoresContainerPathsMountedSomewhereElse() throws Exception {
        ContainerEnvironment environment = environment(mountInfo(
            "4 1 8:1 /var/lib/docker/containers/" + ID + "/hostname /srv/other rw - ext4 /dev/sda1 rw"), null);

        assertFalse(environment.insideContainer());
        assertEquals(List.of(), environment.containerIdCandidates());
    }

    @Test
    void fallsBackToTheHostnameOnly() throws Exception {
        ContainerEnvironment environment = environment(mountInfo("1 2 0:1 / / rw - ext4 /dev/sda1 rw"), " abc123 ");

        assertEquals(List.of("abc123"), environment.containerIdCandidates());
    }

    @Test
    void aMarkerFileMeansInsideAContainer() throws Exception {
        Path marker = Files.createFile(tempDir.resolve(".dockerenv"));
        ContainerEnvironment environment = new ContainerEnvironment(tempDir.resolve("missing"), List.of(marker), () -> null);

        assertTrue(environment.insideContainer());
        assertEquals(List.of(), environment.containerIdCandidates());
    }

    @Test
    void notInsideAContainerWithoutMarkersOrMountedHostFiles() throws Exception {
        assertFalse(environment(mountInfo("1 2 0:1 / / rw - ext4 /dev/sda1 rw"), "host").insideContainer());
    }

    private Path mountInfo(String... lines) throws Exception {
        return Files.write(tempDir.resolve("mountinfo"), List.of(lines));
    }

    private ContainerEnvironment environment(Path mountInfo, String hostname) {
        return new ContainerEnvironment(mountInfo, List.of(tempDir.resolve("no-marker")), () -> hostname);
    }
}
