package wellatleastitried.mediagarrdServer.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ServiceDefinitionTest {

    @Test
    void normalizesNameAndPaths() {
        ServiceDefinition service = new ServiceDefinition("  radarr ",
            Arrays.asList(" /srv/radarr/config/ ", null, "", "/srv/radarr/./data", "/srv/radarr/config"));

        assertEquals("radarr", service.name());
        assertEquals(List.of("/srv/radarr/config", "/srv/radarr/data"), service.paths());
    }

    @Test
    void treatsMissingFieldsAsEmpty() {
        ServiceDefinition service = new ServiceDefinition(null, null);

        assertEquals("", service.name());
        assertEquals(List.of(), service.paths());
    }

    @ParameterizedTest
    @ValueSource(strings = {"radarr", "Radarr-4K", "qbit.torrent", "a", "service_2"})
    void acceptsValidNames(String name) {
        assertDoesNotThrow(() -> new ServiceDefinition(name, List.of("/srv/data")).validate());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"../etc", "-radarr", ".hidden", "has space", "a/b", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void rejectsInvalidNames(String name) {
        assertThrows(IllegalArgumentException.class, () -> new ServiceDefinition(name, List.of("/srv/data")).validate());
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative/path", "/", "/..", "/srv/bad\npath"})
    void rejectsInvalidPaths(String path) {
        assertThrows(IllegalArgumentException.class, () -> new ServiceDefinition("radarr", List.of(path)).validate());
    }

    @Test
    void requiresAtLeastOnePath() {
        assertThrows(IllegalArgumentException.class, () -> new ServiceDefinition("radarr", List.of(" ")).validate());
    }
}
