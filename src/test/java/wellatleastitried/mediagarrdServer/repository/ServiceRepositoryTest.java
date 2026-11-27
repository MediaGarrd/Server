package wellatleastitried.mediagarrdServer.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import wellatleastitried.mediagarrdServer.model.ServiceDefinition;

class ServiceRepositoryTest extends DatabaseTestBase {

    private static final ServiceDefinition RADARR = new ServiceDefinition("radarr", List.of("/srv/radarr/config", "/srv/radarr/data"));

    @Test
    void storesAndFetchesServicesWithPathOrderPreserved() {
        assertEquals(List.of(), db.services.fetchServices());

        db.services.addService(RADARR);

        assertEquals(Optional.of(RADARR), db.services.fetchService("radarr"));
        assertEquals(Optional.of(RADARR), db.services.fetchService("RADARR"));
        assertEquals(List.of(RADARR), db.services.fetchServices());
    }

    @Test
    void listsServicesSortedByNameIgnoringCase() {
        db.services.addService(new ServiceDefinition("sonarr", List.of("/a")));
        db.services.addService(new ServiceDefinition("Jellyfin", List.of("/b")));
        db.services.addService(new ServiceDefinition("radarr", List.of("/c")));

        assertEquals(List.of("Jellyfin", "radarr", "sonarr"), db.services.fetchServices().stream().map(ServiceDefinition::name).toList());
    }

    @Test
    void rejectsDuplicateServiceNamesIgnoringCase() {
        db.services.addService(RADARR);

        assertThrows(IllegalStateException.class, () -> db.services.addService(new ServiceDefinition("Radarr", List.of("/other"))));
    }

    @Test
    void updatesServiceNameAndPaths() {
        db.services.addService(RADARR);
        ServiceDefinition renamed = new ServiceDefinition("radarr-4k", List.of("/srv/radarr-4k"));

        assertTrue(db.services.updateService("radarr", renamed));

        assertTrue(db.services.fetchService("radarr").isEmpty());
        assertEquals(Optional.of(renamed), db.services.fetchService("radarr-4k"));
        assertFalse(db.services.updateService("missing", renamed));
    }

    @Test
    void removesService() {
        db.services.addService(RADARR);

        assertTrue(db.services.removeService("radarr"));
        assertFalse(db.services.removeService("radarr"));
        assertEquals(List.of(), db.services.fetchServices());
    }
}
