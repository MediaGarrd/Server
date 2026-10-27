package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;

class SetupServiceTest {

    @TempDir
    Path tempDir;

    private DatabaseService db;
    private SetupService setup;

    @BeforeEach
    void setUp() {
        MediaGarrdProperties properties = new MediaGarrdProperties();
        properties.setDatabasePath(tempDir.resolve("mediagarrd.db").toString());
        db = new DatabaseService(properties);
        setup = new SetupService(db);
    }

    @Test
    void startsInSetupModeWithSixDigitPin() {
        assertFalse(setup.isCompleted());
        assertTrue(setup.currentPin().matches("[0-9]{6}"));
        assertFalse(setup.canConfigure(null));
        assertFalse(setup.canConfigure("made-up"));
    }

    @Test
    void correctPinStartsSession() {
        Optional<String> session = setup.startSession(" " + setup.currentPin() + " ");

        assertTrue(session.isPresent());
        assertTrue(setup.canConfigure(session.get()));
        assertFalse(setup.canConfigure("made-up"));
    }

    @Test
    void wrongPinIsRejected() {
        assertTrue(setup.startSession(null).isEmpty());
        assertTrue(setup.startSession("not-the-pin").isEmpty());
    }

    @Test
    void pinIsSingleUse() {
        String pin = setup.currentPin();
        assertTrue(setup.startSession(pin).isPresent());
        assumeFalse(pin.equals(setup.currentPin()));

        assertTrue(setup.startSession(pin).isEmpty());
        assertTrue(setup.startSession(setup.currentPin()).isPresent());
    }

    @Test
    void rotatesPinAfterTooManyFailedAttempts() {
        String pin = setup.currentPin();
        for (int i = 0; i < SetupService.MAX_FAILED_ATTEMPTS; i++) {
            setup.startSession("wrong");
        }
        assumeFalse(pin.equals(setup.currentPin()));

        assertTrue(setup.startSession(pin).isEmpty());
    }

    @Test
    void completingSetupPersistsAndDisablesSessions() {
        String session = setup.startSession(setup.currentPin()).orElseThrow();

        setup.complete();

        assertTrue(setup.isCompleted());
        assertNull(setup.currentPin());
        assertTrue(setup.startSession("123456").isEmpty());
        assertTrue(setup.canConfigure(null));
        assertTrue(setup.canConfigure(session));

        SetupService restarted = new SetupService(db);
        assertTrue(restarted.isCompleted());
        assertNull(restarted.currentPin());
    }

    @Test
    void restartWhileInSetupIssuesFreshPin() {
        String session = setup.startSession(setup.currentPin()).orElseThrow();

        SetupService restarted = new SetupService(db);

        assertFalse(restarted.isCompleted());
        assertFalse(restarted.canConfigure(session));
        assertEquals(6, restarted.currentPin().length());
    }
}
