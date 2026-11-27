package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.repository.TestDatabase;

class SetupServiceTest {

    @TempDir
    Path tempDir;

    private TestDatabase db;
    private final MutableClock clock = new MutableClock();
    private SetupService setup;

    @BeforeEach
    void setUp() {
        MediaGarrdProperties properties = new MediaGarrdProperties();
        properties.setDatabasePath(tempDir.resolve("mediagarrd.db").toString());
        db = new TestDatabase(properties);
        setup = new SetupService(db.settings, db.pairedClients, false, clock);
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

        assertThrows(LockedOutException.class, () -> setup.startSession(pin));
        clock.advance(SetupService.LOCK_DURATION.plusSeconds(1));
        assertTrue(setup.startSession(pin).isEmpty());
    }

    @Test
    void completingSetupPersistsAndDisablesSessions() {
        String session = setup.startSession(setup.currentPin()).orElseThrow();

        setup.complete();

        assertTrue(setup.isCompleted());
        assertTrue(setup.startSession("123456").isEmpty());
        assertFalse(setup.canConfigure(null));
        assertFalse(setup.canConfigure(session));

        SetupService restarted = new SetupService(db.settings, db.pairedClients, false, clock);
        assertTrue(restarted.isCompleted());
        assertTrue(restarted.isPairingOpen());
        assertTrue(restarted.currentPin().matches("[0-9]{6}"));
    }

    @Test
    void completingSetupOpensPairingWithAFreshPinWhenNoClientIsPaired() {
        String setupPin = setup.currentPin();

        setup.complete();

        assertTrue(setup.isPairingOpen());
        assertTrue(setup.currentPin().matches("[0-9]{6}"));
        assumeFalse(setupPin.equals(setup.currentPin()));
        assertTrue(setup.startSession(setup.currentPin()).isEmpty());
    }

    @Test
    void completingSetupLeavesPairingClosedWhenAClientIsAlreadyPaired() {
        db.pairedClients.addPairedClient("c1", "Client", "hash", Instant.now());

        setup.complete();

        assertFalse(setup.isPairingOpen());
        assertNull(setup.currentPin());
    }

    @Test
    void startingCompletedWithClientsKeepsPairingClosed() {
        setup.complete();
        db.pairedClients.addPairedClient("c1", "Client", "hash", Instant.now());

        SetupService restarted = new SetupService(db.settings, db.pairedClients, false, clock);

        assertFalse(restarted.isPairingOpen());
        assertNull(restarted.currentPin());
    }

    @Test
    void openPairingFlagOpensPairingEvenWithPairedClients() {
        setup.complete();
        db.pairedClients.addPairedClient("c1", "Client", "hash", Instant.now());

        SetupService restarted = new SetupService(db.settings, db.pairedClients, true, clock);

        assertTrue(restarted.isPairingOpen());
        assertTrue(restarted.currentPin().matches("[0-9]{6}"));
    }

    @Test
    void openPairingFlagDoesNothingBeforeSetupCompletes() {
        SetupService restarted = new SetupService(db.settings, db.pairedClients, true, clock);

        assertFalse(restarted.isPairingOpen());
        assertFalse(restarted.isCompleted());
    }

    @Test
    void tooManyWrongPinsLockEntryForTenMinutes() {
        for (int i = 0; i < SetupService.MAX_FAILED_ATTEMPTS; i++) {
            assertTrue(setup.startSession("wrong").isEmpty());
        }

        LockedOutException locked = assertThrows(LockedOutException.class, () -> setup.startSession(setup.currentPin()));
        assertTrue(locked.retryAfter().compareTo(Duration.ofMinutes(10)) <= 0);
        assertTrue(locked.retryAfter().compareTo(Duration.ofMinutes(9)) > 0);

        clock.advance(Duration.ofMinutes(9));
        assertThrows(LockedOutException.class, () -> setup.startSession(setup.currentPin()));

        clock.advance(Duration.ofMinutes(2));
        assertTrue(setup.startSession(setup.currentPin()).isPresent());
    }

    @Test
    void setupAndPairingShareOneLockout() {
        setup.complete();
        for (int i = 0; i < SetupService.MAX_FAILED_ATTEMPTS; i++) {
            assertThrows(PairingException.class, () -> setup.checkPairingPin("000000x"));
        }

        assertThrows(LockedOutException.class, () -> setup.checkPairingPin(setup.currentPin()));
        assertTrue(setup.isPairingOpen());

        clock.advance(Duration.ofMinutes(11));
        long epoch = setup.checkPairingPin(setup.currentPin());
        assertTrue(setup.isPairingOpen());
        assertTrue(setup.closePairing(epoch));
        assertFalse(setup.isPairingOpen());
        assertNull(setup.currentPin());
    }

    @Test
    void pairingPinIsRefusedBeforeSetupCompletes() {
        PairingException e = assertThrows(PairingException.class, () -> setup.checkPairingPin(setup.currentPin()));

        assertEquals(PairingException.Reason.SETUP_INCOMPLETE, e.reason());
    }

    @Test
    void closedPairingRefusesThePin() {
        setup.complete();
        String pin = setup.currentPin();
        setup.closePairing(setup.checkPairingPin(pin));

        PairingException e = assertThrows(PairingException.class, () -> setup.checkPairingPin(pin));

        assertEquals(PairingException.Reason.CLOSED, e.reason());
    }

    @Test
    void aCorrectPinLeavesPairingOpenUntilItIsClosed() {
        setup.complete();
        String pin = setup.currentPin();

        long first = setup.checkPairingPin(pin);
        long second = setup.checkPairingPin(pin);

        assertEquals(first, second);
        assertTrue(setup.isPairingOpen());
        assertEquals(pin, setup.currentPin());
        assertTrue(setup.isPairingOpen(first));
    }

    @Test
    void aMatchingPinDoesNotResetTheLockoutCounter() {
        setup.complete();
        for (int i = 0; i < SetupService.MAX_FAILED_ATTEMPTS - 1; i++) {
            assertThrows(PairingException.class, () -> setup.checkPairingPin("000000x"));
        }
        long epoch = setup.checkPairingPin(setup.currentPin());
        assertThrows(PairingException.class, () -> setup.checkPairingPin("000000x"));
        assertThrows(LockedOutException.class, () -> setup.checkPairingPin(setup.currentPin()));
        assertTrue(setup.isPairingOpen(epoch));
    }

    @Test
    void closingPairingFailsForAnEpochFromEarlierOpening() {
        setup.complete();
        long epoch = setup.checkPairingPin(setup.currentPin());
        assertTrue(setup.closePairing(epoch));
        assertFalse(setup.closePairing(epoch));

        setup.reopenPairing();

        assertFalse(setup.isPairingOpen(epoch));
        assertFalse(setup.closePairing(epoch));
        assertTrue(setup.isPairingOpen());
    }

    @Test
    void rotatingThePinAfterALockoutKeepsTheEpoch() {
        setup.complete();
        long epoch = setup.checkPairingPin(setup.currentPin());
        for (int i = 0; i < SetupService.MAX_FAILED_ATTEMPTS; i++) {
            assertThrows(PairingException.class, () -> setup.checkPairingPin("000000x"));
        }

        assertTrue(setup.isPairingOpen(epoch));
    }

    @Test
    void completeReturnsThePairingPinOrNullWhenAClientIsPaired() {
        String pin = setup.complete();

        assertEquals(setup.currentPin(), pin);
        assertTrue(pin.matches("[0-9]{6}"));
    }

    @Test
    void completingTwiceIsRefusedAndKeepsThePinAndEpoch() {
        String pin = setup.complete();
        long epoch = setup.checkPairingPin(pin);

        assertThrows(SetupAlreadyCompleteException.class, () -> setup.complete());

        assertEquals(pin, setup.currentPin());
        assertTrue(setup.isPairingOpen(epoch));
    }

    @Test
    void completeReturnsNullWhenAClientIsAlreadyPaired() {
        db.pairedClients.addPairedClient("a", "A", "hash", Instant.parse("2026-11-19T10:00:00Z"));

        assertNull(setup.complete());
        assertFalse(setup.isPairingOpen());
    }

    @Test
    void reopeningPairingIssuesANewPin() {
        setup.complete();
        setup.closePairing(setup.checkPairingPin(setup.currentPin()));

        setup.reopenPairing();

        assertTrue(setup.isPairingOpen());
        assertTrue(setup.currentPin().matches("[0-9]{6}"));
    }

    @Test
    void restartWhileInSetupIssuesFreshPin() {
        String session = setup.startSession(setup.currentPin()).orElseThrow();

        SetupService restarted = new SetupService(db.settings, db.pairedClients, false, clock);

        assertFalse(restarted.isCompleted());
        assertFalse(restarted.canConfigure(session));
        assertEquals(6, restarted.currentPin().length());
    }

    @Test
    void reopeningPairingKeepsTheLockout() {
        setup.complete();
        for (int i = 0; i < SetupService.MAX_FAILED_ATTEMPTS; i++) {
            assertThrows(PairingException.class, () -> setup.checkPairingPin("000000x"));
        }

        setup.reopenPairing();
        assertThrows(LockedOutException.class, () -> setup.checkPairingPin(setup.currentPin()));
    }

    @Test
    void setupSessionsExpireAfterBeingIdle() {
        String session = setup.startSession(setup.currentPin()).orElseThrow();

        clock.advance(SetupService.SESSION_IDLE_TIMEOUT.minusMinutes(1));
        assertTrue(setup.canConfigure(session));

        clock.advance(SetupService.SESSION_IDLE_TIMEOUT.minusMinutes(1));
        assertTrue(setup.canConfigure(session));

        clock.advance(SetupService.SESSION_IDLE_TIMEOUT.plusMinutes(1));
        assertFalse(setup.canConfigure(session));
    }

    @Test
    void overlongPinsAreABadRequest() {
        assertThrows(IllegalArgumentException.class, () -> setup.startSession("1".repeat(SetupService.MAX_PIN_LENGTH + 1)));
    }
}
