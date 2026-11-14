package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Volume;

class DatabaseHostPathResolverTest {

    @TempDir
    Path tempDir;

    private final DockerService docker = mock(DockerService.class);
    private final ContainerEnvironment environment = mock(ContainerEnvironment.class);
    private final DatabaseHostPathResolver resolver = new DatabaseHostPathResolver(docker, environment);

    private Path database;
    private Path databaseDirectory;

    @BeforeEach
    void setUp() throws Exception {
        databaseDirectory = Files.createDirectories(tempDir.toRealPath().resolve("db"));
        database = Files.createFile(databaseDirectory.resolve("mediagarrd.db"));
        when(environment.insideContainer()).thenReturn(true);
        when(environment.containerIdCandidates()).thenReturn(List.of("abc"));
    }

    @Test
    void usesThePathAsIsOutsideAContainer() throws Exception {
        when(environment.insideContainer()).thenReturn(false);

        assertEquals(database, resolver.resolve(database));

        verify(docker, never()).mountsOf(anyString());
    }

    @Test
    void mapsThroughABindMount() throws Exception {
        when(docker.mountsOf("abc")).thenReturn(List.of(mount(databaseDirectory.toString(), "/home/u/data/server/db")));

        assertEquals(Path.of("/home/u/data/server/db/mediagarrd.db"), resolver.resolve(database));
    }

    @Test
    void mapsThroughANamedVolume() throws Exception {
        when(docker.mountsOf("abc")).thenReturn(List.of(mount(databaseDirectory.toString(), "/var/lib/docker/volumes/db/_data")));

        assertEquals(Path.of("/var/lib/docker/volumes/db/_data/mediagarrd.db"), resolver.resolve(database));
    }

    @Test
    void picksTheLongestMatchingMount() throws Exception {
        when(docker.mountsOf("abc")).thenReturn(List.of(
            mount(tempDir.toRealPath().toString(), "/outer"),
            mount(databaseDirectory.toString(), "/inner"),
            mount("/", "/rootfs")));

        assertEquals(Path.of("/inner/mediagarrd.db"), resolver.resolve(database));
    }

    @Test
    void doesNotMatchASiblingWithTheSamePrefix() throws Exception {
        Path sibling = Files.createDirectories(tempDir.toRealPath().resolve("dbx"));
        when(docker.mountsOf("abc")).thenReturn(List.of(mount(sibling.toString(), "/elsewhere")));

        IOException e = assertThrows(IOException.class, () -> resolver.resolve(database));

        assertTrue(e.getMessage().contains("no mount covers"));
    }

    @Test
    void mapsAMountOfTheDatabaseFileItself() throws Exception {
        when(docker.mountsOf("abc")).thenReturn(List.of(mount(database.toString(), "/home/u/mediagarrd.db")));

        assertEquals(Path.of("/home/u/mediagarrd.db"), resolver.resolve(database));
    }

    @Test
    void failsForAMountWithoutAHostSource() {
        when(docker.mountsOf("abc")).thenReturn(List.of(mount(databaseDirectory.toString(), "")));

        IOException e = assertThrows(IOException.class, () -> resolver.resolve(database));

        assertTrue(e.getMessage().contains("no host source"));
    }

    @Test
    void failsWhenTheDatabaseIsInTheWritableLayer() {
        when(docker.mountsOf("abc")).thenReturn(List.of(mount("/tmp/else", "/home/u/else")));

        assertThrows(IOException.class, () -> resolver.resolve(database));
    }

    @Test
    void failsWhenInspectThrows() {
        when(docker.mountsOf("abc")).thenThrow(new NotFoundException("gone"));

        IOException e = assertThrows(IOException.class, () -> resolver.resolve(database));

        assertTrue(e.getMessage().contains("gone"));
    }

    @Test
    void failsWhenTheContainerIdIsUnknown() {
        when(environment.containerIdCandidates()).thenReturn(List.of());

        assertThrows(IOException.class, () -> resolver.resolve(database));
    }

    @Test
    void failsWhenTheSourceIsNotAbsolute() {
        when(docker.mountsOf("abc")).thenReturn(List.of(mount(databaseDirectory.toString(), "relative/db")));

        assertThrows(IOException.class, () -> resolver.resolve(database));
    }

    @Test
    void fallsBackToTheNextCandidateWhenTheFirstDoesNotCoverTheDatabase() throws Exception {
        when(environment.containerIdCandidates()).thenReturn(List.of("host-name", "abc"));
        when(docker.mountsOf("host-name")).thenThrow(new NotFoundException("no such container"));
        when(docker.mountsOf("abc")).thenReturn(List.of(mount(databaseDirectory.toString(), "/home/u/db")));

        assertEquals(Path.of("/home/u/db/mediagarrd.db"), resolver.resolve(database));
    }

    @Test
    void failsWhenTheDatabaseFileDoesNotExist() throws Exception {
        Files.delete(database);

        assertThrows(IOException.class, () -> resolver.resolve(database));
    }

    private static InspectContainerResponse.Mount mount(String destination, String source) {
        return new InspectContainerResponse.Mount().withDestination(new Volume(destination)).withSource(source);
    }
}
