package wellatleastitried.mediagarrdServer.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class PairedClientRepositoryTest extends DatabaseTestBase {

    @Test
    void pairedClientsTableIsAddedToAnExistingDatabase() throws Exception {
        db = null;
        Files.delete(Path.of(properties.getDatabasePath()));
        try (var connection = rawConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("PRAGMA user_version = 2");
        }

        TestDatabase upgraded = new TestDatabase(properties);

        assertEquals(0, upgraded.pairedClients.countPairedClients());
        upgraded.pairedClients.addPairedClient("c1", "Laptop", "hash-1", Instant.parse("2026-11-19T10:00:00Z"));
        assertEquals("Laptop", upgraded.pairedClients.fetchPairedClientByTokenHash("hash-1").orElseThrow().name());
        assertTrue(upgraded.pairedClients.fetchPairedClientByTokenHash("other").isEmpty());
    }

    @Test
    void pairedClientsCanBeTouchedListedAndRemoved() {
        Instant created = Instant.parse("2026-11-19T10:00:00Z");
        db.pairedClients.addPairedClient("c1", "Laptop", "hash-1", created);
        db.pairedClients.addPairedClient("c2", "Phone", "hash-2", created.plusSeconds(5));

        db.pairedClients.touchPairedClient("c2", created.plusSeconds(60));

        var clients = db.pairedClients.fetchPairedClients();
        assertEquals(List.of("c1", "c2"), clients.stream().map(c -> c.id()).toList());
        assertNull(clients.get(0).lastUsedAt());
        assertEquals(created.plusSeconds(60), clients.get(1).lastUsedAt());
        assertTrue(db.pairedClients.removePairedClient("c1"));
        assertFalse(db.pairedClients.removePairedClient("c1"));
        assertEquals(1, db.pairedClients.countPairedClients());
    }

    @Test
    void tokenHashesAreUnique() {
        db.pairedClients.addPairedClient("c1", "Laptop", "hash-1", Instant.now());

        assertThrows(IllegalStateException.class, () -> db.pairedClients.addPairedClient("c2", "Phone", "hash-1", Instant.now()));
    }
}
