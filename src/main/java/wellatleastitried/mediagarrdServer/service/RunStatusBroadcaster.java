package wellatleastitried.mediagarrdServer.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;

@Service
public class RunStatusBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger(RunStatusBroadcaster.class);

    static final int MAX_SUBSCRIBERS = 16;
    static final long EMITTER_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(6);
    static final long ROLLOVER_MS = TimeUnit.SECONDS.toMillis(270);
    static final long PING_INTERVAL_MS = TimeUnit.SECONDS.toMillis(20);
    static final int QUEUE_CAPACITY = 8;

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    private static final Message CLOSE = new Message("close", "");

    private final Object lock = new Object();
    private final List<Subscriber> subscribers = new ArrayList<>();
    private final int maxSubscribers;
    private final long emitterTimeoutMs;
    private final long pingIntervalMs;
    private final long rolloverMs;
    private final ThreadFactory threadFactory;
    private boolean running;
    private boolean shutdown;

    public RunStatusBroadcaster() {
        this(MAX_SUBSCRIBERS, EMITTER_TIMEOUT_MS, PING_INTERVAL_MS, ROLLOVER_MS, RunStatusBroadcaster::newWriterThread);
    }

    RunStatusBroadcaster(int maxSubscribers, long emitterTimeoutMs, long pingIntervalMs, long rolloverMs, ThreadFactory threadFactory) {
        this.maxSubscribers = maxSubscribers;
        this.emitterTimeoutMs = emitterTimeoutMs;
        this.pingIntervalMs = pingIntervalMs;
        this.rolloverMs = rolloverMs;
        this.threadFactory = threadFactory;
    }

    private static Thread newWriterThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "run-events-writer-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    public SseEmitter subscribe() {
        return subscribe(new SseEmitter(emitterTimeoutMs));
    }

    SseEmitter subscribe(SseEmitter emitter) {
        Subscriber subscriber = new Subscriber(emitter);
        synchronized (lock) {
            if (shutdown || subscribers.size() >= maxSubscribers) {
                throw new SubscriberLimitException("Too many event stream subscribers");
            }
            subscriber.queue.offer(new Message("state", stateJson(running, Instant.now())));
            subscribers.add(subscriber);
        }
        emitter.onTimeout(subscriber::close);
        emitter.onCompletion(subscriber::ended);
        emitter.onError(error -> subscriber.ended());
        Thread writer = threadFactory.newThread(subscriber::writeLoop);
        writer.start();
        return emitter;
    }

    public void publishStarted(Instant startedAt) {
        publish("run-started", Boolean.TRUE, "{\"running\":true,\"startedAt\":\"" + startedAt + "\"}");
    }

    public void publishFinished(String status, Instant startedAt, Instant finishedAt) {
        publish("run-finished", Boolean.FALSE, "{\"running\":false,\"status\":\"" + status.toUpperCase(Locale.ROOT)
            + "\",\"startedAt\":\"" + startedAt + "\",\"finishedAt\":\"" + finishedAt + "\"}");
    }

    public void publishBackupsChanged() {
        publish("backups-changed", null, atJson());
    }

    public void publishConfigChanged() {
        publish("config-changed", null, atJson());
    }

    private static String atJson() {
        return "{\"at\":\"" + Instant.now() + "\"}";
    }

    private void publish(String name, Boolean nowRunning, String json) {
        Message message = new Message(name, json);
        synchronized (lock) {
            if (nowRunning != null) {
                running = nowRunning;
            }
            for (Subscriber subscriber : new ArrayList<>(subscribers)) {
                if (!subscriber.queue.offer(message)) {
                    LOGGER.warn("Event stream subscriber is not keeping up, dropping it");
                    subscribers.remove(subscriber);
                    subscriber.close();
                }
            }
        }
    }

    int subscriberCount() {
        synchronized (lock) {
            return subscribers.size();
        }
    }

    @PreDestroy
    public void shutdown() {
        List<Subscriber> toClose;
        synchronized (lock) {
            shutdown = true;
            toClose = new ArrayList<>(subscribers);
            subscribers.clear();
        }
        toClose.forEach(Subscriber::close);
    }

    private static String stateJson(boolean running, Instant at) {
        return "{\"running\":" + running + ",\"at\":\"" + at + "\"}";
    }

    private record Message(String name, String data) {
    }

    public static class SubscriberLimitException extends RuntimeException {
        public SubscriberLimitException(String message) {
            super(message);
        }
    }

    private final class Subscriber {

        private final SseEmitter emitter;
        private final BlockingQueue<Message> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean ended = new AtomicBoolean();

        Subscriber(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void close() {
            closed.set(true);
            queue.offer(CLOSE);
        }

        void ended() {
            ended.set(true);
            synchronized (lock) {
                subscribers.remove(this);
            }
            close();
        }

        void writeLoop() {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(rolloverMs);
            try {
                while (!closed.get()) {
                    long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remainingMs <= 0) {
                        break;
                    }
                    Message message = queue.poll(Math.min(pingIntervalMs, remainingMs), TimeUnit.MILLISECONDS);
                    if (message == null) {
                        if (System.nanoTime() - deadline >= 0) {
                            break;
                        }
                        message = new Message("ping", "{}");
                    }
                    if (message == CLOSE || closed.get()) {
                        break;
                    }
                    emitter.send(SseEmitter.event().name(message.name()).data(message.data()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("Event stream write failed, dropping subscriber: {}", e.toString());
                closed.set(true);
                synchronized (lock) {
                    subscribers.remove(this);
                }
                finish(e);
                return;
            }
            synchronized (lock) {
                subscribers.remove(this);
            }
            finish(null);
        }

        private void finish(Exception error) {
            if (ended.get()) {
                return;
            }
            try {
                if (error == null) {
                    emitter.complete();
                } else {
                    emitter.completeWithError(error);
                }
            } catch (RuntimeException e) {
                LOGGER.debug("Completing the event stream failed: {}", e.toString());
            }
        }
    }
}
