package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import wellatleastitried.mediagarrdServer.model.BackupRunResult;

class BackupRunLifecycleTest {

    private final RunStatusBroadcaster broadcaster = mock(RunStatusBroadcaster.class);
    private final BackupRunLifecycle lifecycle = new BackupRunLifecycle(broadcaster);
    private final Instant start = Instant.parse("2026-11-20T10:00:00Z");

    private static BackupRunResult result(String status) {
        return new BackupRunResult("run", "s", "e", status, List.of(), null, null);
    }

    @Test
    void aStartedRunPublishesStartedThenFinishedWithItsStatus() {
        lifecycle.run(start, run -> {
            run.markStarted();
            assertTrue(lifecycle.isRunning());
            return result(COMPLETED);
        });

        InOrder order = inOrder(broadcaster);
        order.verify(broadcaster).publishStarted(start);
        order.verify(broadcaster).publishFinished(eq(COMPLETED), eq(start), any());
        assertFalse(lifecycle.isRunning());
    }

    @Test
    void aBodyThatThrowsFinishesAsFailedAndReleasesTheRun() {
        assertThrows(IllegalStateException.class, () -> lifecycle.run(start, run -> {
            run.markStarted();
            throw new IllegalStateException("boom");
        }));

        verify(broadcaster).publishFinished(eq(FAILED), eq(start), any());
        assertFalse(lifecycle.isRunning());
        assertEquals(COMPLETED, lifecycle.run(start, run -> result(COMPLETED)).status());
    }

    @Test
    void aRunThatNeverStartedPublishesNothing() {
        assertThrows(RunRejectedException.class, () -> lifecycle.run(start, run -> {
            throw new RunRejectedException("No services are configured for backup");
        }));

        verify(broadcaster, never()).publishStarted(any());
        verify(broadcaster, never()).publishFinished(any(), any(), any());
        assertFalse(lifecycle.isRunning());
    }

    @Test
    void markingStartedTwicePublishesOneStartedEvent() {
        lifecycle.run(start, run -> {
            run.markStarted();
            run.markStarted();
            return result(COMPLETED);
        });

        verify(broadcaster, times(1)).publishStarted(start);
    }

    @Test
    void aSecondRunIsRejectedWhileOneIsRunning() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<BackupRunResult> first = CompletableFuture.supplyAsync(() -> lifecycle.run(start, run -> {
            inside.countDown();
            await(release);
            return result(COMPLETED);
        }));
        assertTrue(inside.await(5, TimeUnit.SECONDS));

        assertThrows(RunRejectedException.class, () -> lifecycle.run(start, run -> result(COMPLETED)));

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
    }

    @Test
    void whileIdleIsRefusedWhileARunHoldsTheConfigLockAndWorksAfterwards() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<BackupRunResult> running = CompletableFuture.supplyAsync(() -> lifecycle.run(start, run -> {
            inside.countDown();
            await(release);
            return result(COMPLETED);
        }));
        assertTrue(inside.await(5, TimeUnit.SECONDS));

        assertThrows(BackupInProgressException.class, () -> lifecycle.whileIdle(() -> "changed"));

        release.countDown();
        running.get(5, TimeUnit.SECONDS);
        assertEquals("changed", lifecycle.whileIdle(() -> "changed"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
