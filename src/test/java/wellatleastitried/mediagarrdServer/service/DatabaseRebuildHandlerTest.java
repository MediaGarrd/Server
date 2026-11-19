package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.dto.PairResponse;
import wellatleastitried.mediagarrdServer.repository.TestDatabase;

class DatabaseRebuildHandlerTest {

    @TempDir
    Path tempDir;

    private final MutableClock clock = new MutableClock();
    private Path databaseFile;
    private TestDatabase db;
    private SetupService setup;
    private RunStatusBroadcaster broadcaster;
    private PairingService pairing;
    private BackupArchiveService archives;
    private BackupScheduleService schedule;
    private Path defaultRoot;

    @BeforeEach
    void setUp() {
        MediaGarrdProperties properties = new MediaGarrdProperties();
        databaseFile = tempDir.resolve("mediagarrd.db");
        properties.setDatabasePath(databaseFile.toString());
        defaultRoot = tempDir.resolve("default-backups");
        properties.setBackupRoot(defaultRoot.toString());
        db = new TestDatabase(properties);
        broadcaster = new RunStatusBroadcaster();
        setup = new SetupService(db.settings, db.pairedClients, false, clock);
        pairing = new PairingService(setup, db.pairedClients, broadcaster, clock);
        archives = new BackupArchiveService(properties, db.settings, db.backups);
        schedule = new BackupScheduleService(properties, db.settings, db.backups, setup, clock);
        new DatabaseRebuildHandler(db.database, pairing, broadcaster, archives, schedule);
    }

    private void corruptWhileRunning() throws Exception {
        Files.writeString(databaseFile, "x".repeat(4096));
        db.settings.fetchSetting("anything");
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met in time");
            }
            Thread.sleep(10);
        }
    }

    private PairResponse pairConfirmed(String name) {
        PairResponse response = pairing.pair(setup.currentPin(), name);
        assertTrue(pairing.authenticate(response.token()).isPresent());
        return response;
    }

    @Test
    void aRuntimeRebuildReopensSetupLikeARestartOnAFreshDatabase() throws Exception {
        setup.complete();
        PairResponse client = pairConfirmed("Laptop");
        pairing.createCode();
        assertEquals(1, db.pairedClients.countPairedClients());

        corruptWhileRunning();
        await(() -> !setup.isCompleted());

        assertEquals(0, db.pairedClients.countPairedClients());
        assertFalse(setup.isPairingOpen());
        String pin = setup.currentPin();
        assertNotNull(pin);
        assertTrue(pairing.authenticate(client.token()).isEmpty());
        String session = setup.startSession(pin).orElseThrow();
        assertTrue(setup.canConfigure(session));
    }

    @Test
    void theBackupLocationRetentionAndScheduleAreReloadedFromTheNewDatabase() throws Exception {
        setup.complete();
        archives.updateSettings(tempDir.resolve("custom-backups").toString(), 7);
        schedule.updateInterval(Duration.ofHours(3));
        assertEquals(7, archives.retentionCount());
        assertEquals(Duration.ofHours(3), schedule.getInterval());

        corruptWhileRunning();
        await(() -> !setup.isCompleted());
        await(() -> archives.rootDirectory().equals(defaultRoot.toAbsolutePath().normalize()));
        await(() -> !Duration.ofHours(3).equals(schedule.getInterval()));

        assertEquals(new MediaGarrdProperties().getRetentionCount(), archives.retentionCount());
        assertEquals(new MediaGarrdProperties().getBackupInterval(), schedule.getInterval());
    }

    @Test
    void aPendingPairingFromBeforeTheRebuildCannotConfirmAfterwards() throws Exception {
        setup.complete();
        PairResponse pending = pairing.pair(setup.currentPin(), "Phone");

        corruptWhileRunning();
        await(() -> !setup.isCompleted());
        setup.complete();

        assertTrue(pairing.authenticate(pending.token()).isEmpty());
        assertEquals(0, db.pairedClients.countPairedClients());
    }

    @Test
    void aLockoutFromBeforeTheRebuildIsCleared() throws Exception {
        setup.complete();
        for (int i = 0; i < SetupService.MAX_FAILED_ATTEMPTS; i++) {
            assertThrows(PairingException.class, () -> pairing.pair("000000", null));
        }
        assertThrows(LockedOutException.class, () -> pairing.pair("000000", null));

        corruptWhileRunning();
        await(() -> !setup.isCompleted());

        assertTrue(setup.startSession(setup.currentPin()).isPresent());
    }

    @Test
    void openEventStreamsAreClosedButNewOnesStillWork() throws Exception {
        broadcaster.subscribe(new SseEmitter(60_000L));
        assertEquals(1, broadcaster.subscriberCount());

        corruptWhileRunning();
        await(() -> broadcaster.subscriberCount() == 0);

        broadcaster.subscribe(new SseEmitter(60_000L));
        assertEquals(1, broadcaster.subscriberCount());
        broadcaster.shutdown();
    }

    @Test
    void theRebuildDoesNotWaitForTheSetupServiceMonitorOrAQueuedSnapshot() throws Exception {
        setup.complete();
        Path snapshot = tempDir.resolve("snapshot.db");
        Files.writeString(databaseFile, "x".repeat(4096));

        CompletableFuture<Void> rebuild;
        CompletableFuture<Void> snapshotting;
        synchronized (setup) {
            rebuild = CompletableFuture.runAsync(() -> db.settings.fetchSetting("anything"));
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> rebuild.get());
            snapshotting = CompletableFuture.runAsync(() -> {
                try {
                    db.database.snapshotTo(snapshot);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> snapshotting.get());
            assertTrue(setup.isCompleted());
        }

        await(() -> !setup.isCompleted());
        assertTrue(Files.isRegularFile(snapshot));
    }
}
