package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Timeout(30)
class RunStatusBroadcasterTest {

    private static final Instant STARTED = Instant.parse("2026-11-11T10:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-11-11T10:05:00Z");

    private final CountDownLatch writerExited = new CountDownLatch(1);
    private final List<TestEmitter> emitters = new ArrayList<>();
    private RunStatusBroadcaster broadcaster = newBroadcaster(16, 60_000);

    @AfterEach
    void tearDown() {
        broadcaster.shutdown();
    }

    @Test
    void sendsTheCurrentStateFirstWhenIdle() throws Exception {
        TestEmitter emitter = subscribe();

        String frame = emitter.next();

        assertTrue(frame.startsWith("event:state\ndata:{\"running\":false,\"at\":\""), frame);
        assertTrue(frame.endsWith("\"}\n\n"), frame);
    }

    @Test
    void sendsRunningStateToLateSubscribers() throws Exception {
        broadcaster.publishStarted(STARTED);

        assertTrue(subscribe().next().startsWith("event:state\ndata:{\"running\":true,\"at\":\""));

        broadcaster.publishFinished("completed", STARTED, FINISHED);

        assertTrue(subscribe().next().startsWith("event:state\ndata:{\"running\":false,\"at\":\""));
    }

    @Test
    void fansStartedAndFinishedOutToEverySubscriber() throws Exception {
        TestEmitter first = subscribe();
        TestEmitter second = subscribe();
        first.next();
        second.next();

        broadcaster.publishStarted(STARTED);
        broadcaster.publishFinished("partial", STARTED, FINISHED);

        for (TestEmitter emitter : List.of(first, second)) {
            assertEquals("event:run-started\ndata:{\"running\":true,\"startedAt\":\"2026-11-11T10:00:00Z\"}\n\n", emitter.next());
            assertEquals("event:run-finished\ndata:{\"running\":false,\"status\":\"PARTIAL\","
                + "\"startedAt\":\"2026-11-11T10:00:00Z\",\"finishedAt\":\"2026-11-11T10:05:00Z\"}\n\n", emitter.next());
        }
    }

    @Test
    void publishesBackupsAndConfigChangesWithoutTouchingTheRunState() throws Exception {
        broadcaster.publishStarted(STARTED);
        TestEmitter emitter = subscribe();
        assertTrue(emitter.next().startsWith("event:state\ndata:{\"running\":true"));

        broadcaster.publishBackupsChanged();
        broadcaster.publishConfigChanged();

        String backups = emitter.next();
        String config = emitter.next();
        assertTrue(backups.matches("event:backups-changed\\ndata:\\{\"at\":\"[^\"]+\"\\}\\n\\n"), backups);
        assertTrue(config.matches("event:config-changed\\ndata:\\{\"at\":\"[^\"]+\"\\}\\n\\n"), config);
        assertTrue(subscribe().next().startsWith("event:state\ndata:{\"running\":true"));
    }

    @Test
    void changeNoticesNeverBlockOnAStuckSubscriber() throws Exception {
        TestEmitter stuck = new TestEmitter();
        stuck.blockSends = new CountDownLatch(1);
        broadcaster.subscribe(stuck);
        emitters.add(stuck);
        assertTrue(stuck.sendEntered.await(5, TimeUnit.SECONDS));

        CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 40; i++) {
                broadcaster.publishBackupsChanged();
                broadcaster.publishConfigChanged();
            }
        }).get(5, TimeUnit.SECONDS);

        awaitCount(0);
        stuck.blockSends.countDown();
    }

    @Test
    void sendsANamedPingWhenIdle() throws Exception {
        broadcaster = newBroadcaster(16, 30);
        TestEmitter emitter = subscribe();
        emitter.next();

        assertEquals("event:ping\ndata:{}\n\n", emitter.next());
        assertEquals("event:ping\ndata:{}\n\n", emitter.next());
    }

    @Test
    void rejectsSubscribersBeyondTheCap() {
        broadcaster = newBroadcaster(2, 60_000);
        subscribe();
        subscribe();

        assertThrows(RunStatusBroadcaster.SubscriberLimitException.class, () -> broadcaster.subscribe(new TestEmitter()));
        assertEquals(2, broadcaster.subscriberCount());
    }

    @Test
    void completionFreesASlot() throws Exception {
        broadcaster = newBroadcaster(1, 60_000);
        TestEmitter emitter = subscribe();
        emitter.next();

        emitter.completion.get().run();

        awaitCount(0);
        subscribe();
    }

    @Test
    void errorFreesASlotWithoutCompletingAgain() throws Exception {
        TestEmitter emitter = subscribe();
        emitter.next();

        emitter.error.get().accept(new IOException("reset"));

        awaitCount(0);
        assertTrue(writerExited.await(5, TimeUnit.SECONDS));
        assertEquals(1, emitter.completes.getCount());
        assertEquals(1, emitter.errors.getCount());
    }

    @Test
    void timeoutCompletesTheEmitterOnTheWriterThread() throws Exception {
        TestEmitter emitter = subscribe();
        emitter.next();

        emitter.timeout.get().run();

        assertTrue(emitter.completes.await(5, TimeUnit.SECONDS));
        assertTrue(emitter.completedOn.get().startsWith("run-events-writer-"), emitter.completedOn.get());
        awaitCount(0);
    }

    @Test
    void aFailingSendDropsOnlyThatSubscriber() throws Exception {
        TestEmitter broken = subscribe();
        TestEmitter healthy = subscribe();
        healthy.next();
        broken.failSends = true;

        broadcaster.publishStarted(STARTED);

        assertTrue(healthy.next().startsWith("event:run-started"));
        awaitCount(1);
        assertTrue(broken.errors.await(5, TimeUnit.SECONDS));
    }

    @Test
    void publishingNeverBlocksOnASubscriberStuckInAWrite() throws Exception {
        TestEmitter stuck = new TestEmitter();
        stuck.blockSends = new CountDownLatch(1);
        broadcaster.subscribe(stuck);
        emitters.add(stuck);
        assertTrue(stuck.sendEntered.await(5, TimeUnit.SECONDS));

        long begin = System.nanoTime();
        CompletableFuture<Void> publishing = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 40; i++) {
                broadcaster.publishStarted(STARTED);
                broadcaster.publishFinished("completed", STARTED, FINISHED);
            }
        });
        publishing.get(5, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertTrue(elapsedMs < 1000, "publish took " + elapsedMs + "ms");
        awaitCount(0);
        assertEquals(1, stuck.completes.getCount());

        stuck.blockSends.countDown();
        assertTrue(stuck.completes.await(5, TimeUnit.SECONDS));
        assertTrue(stuck.completedOn.get().startsWith("run-events-writer-"), stuck.completedOn.get());
    }

    @Test
    void shutdownCompletesEverySubscriberAndRefusesNewOnes() throws Exception {
        TestEmitter first = subscribe();
        TestEmitter second = subscribe();
        first.next();
        second.next();

        broadcaster.shutdown();

        assertTrue(first.completes.await(5, TimeUnit.SECONDS));
        assertTrue(second.completes.await(5, TimeUnit.SECONDS));
        assertEquals(0, broadcaster.subscriberCount());
        assertThrows(RunStatusBroadcaster.SubscriberLimitException.class, () -> broadcaster.subscribe(new TestEmitter()));
    }

    @Test
    void createsAnEmitterWithAFiniteTimeout() {
        RunStatusBroadcaster real = new RunStatusBroadcaster();
        try {
            SseEmitter emitter = real.subscribe();
            assertNotNull(emitter);
            assertEquals(RunStatusBroadcaster.EMITTER_TIMEOUT_MS, emitter.getTimeout());
        } finally {
            real.shutdown();
        }
    }

    @Test
    void theWriterEndsTheStreamItselfAtTheRolloverAge() throws Exception {
        broadcaster.shutdown();
        broadcaster = new RunStatusBroadcaster(16, 60_000, 20, 150, runnable -> {
            Thread thread = new Thread(runnable, "run-events-writer-test");
            thread.setDaemon(true);
            return thread;
        });
        TestEmitter emitter = subscribe();
        emitter.next();

        assertTrue(emitter.completes.await(5, TimeUnit.SECONDS));
        assertEquals(1, emitter.errors.getCount());
        assertTrue(emitter.completedOn.get().startsWith("run-events-writer-"), emitter.completedOn.get());
        awaitCount(0);
    }

    @Test
    void rolloverComesBeforeTheEmitterTimeout() {
        assertTrue(RunStatusBroadcaster.ROLLOVER_MS < RunStatusBroadcaster.EMITTER_TIMEOUT_MS);
    }

    private RunStatusBroadcaster newBroadcaster(int cap, long pingMs) {
        return new RunStatusBroadcaster(cap, 60_000, pingMs, 60_000, runnable -> {
            Thread thread = new Thread(() -> {
                try {
                    runnable.run();
                } finally {
                    writerExited.countDown();
                }
            }, "run-events-writer-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    private TestEmitter subscribe() {
        TestEmitter emitter = new TestEmitter();
        emitters.add(emitter);
        broadcaster.subscribe(emitter);
        return emitter;
    }

    private void awaitCount(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (broadcaster.subscriberCount() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, broadcaster.subscriberCount());
    }

    static class TestEmitter extends SseEmitter {

        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CountDownLatch completes = new CountDownLatch(1);
        final CountDownLatch errors = new CountDownLatch(1);
        final CountDownLatch sendEntered = new CountDownLatch(1);
        final AtomicReference<Runnable> timeout = new AtomicReference<>();
        final AtomicReference<Runnable> completion = new AtomicReference<>();
        final AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
        final AtomicReference<String> completedOn = new AtomicReference<>();
        volatile boolean failSends;
        volatile CountDownLatch blockSends;

        TestEmitter() {
            super(60_000L);
        }

        String next() throws InterruptedException {
            String frame = frames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame, "no frame received");
            return frame;
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            sendEntered.countDown();
            if (blockSends != null) {
                try {
                    blockSends.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failSends) {
                throw new IOException("broken pipe");
            }
            StringBuilder frame = new StringBuilder();
            builder.build().forEach(part -> frame.append(part.getData()));
            frames.add(frame.toString());
        }

        @Override
        public void complete() {
            completedOn.set(Thread.currentThread().getName());
            completes.countDown();
        }

        @Override
        public void completeWithError(Throwable ex) {
            errors.countDown();
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeout.set(callback);
        }

        @Override
        public void onCompletion(Runnable callback) {
            completion.set(callback);
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            error.set(callback);
        }
    }
}
