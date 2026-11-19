package wellatleastitried.mediagarrdServer.service;

import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.FAILURE_WEBHOOK_SETTING;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.PARTIAL;

import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;
import wellatleastitried.mediagarrdServer.dto.WebhookStatusDto;
import wellatleastitried.mediagarrdServer.dto.WebhookTestDto;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.repository.SettingsRepository;
import wellatleastitried.mediagarrdServer.utilities.LogSanitizer;
import wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils;

@Service
public class WebhookService {

    public static final String EVENT_FAILED = "backup.failed";
    public static final String EVENT_PARTIAL = "backup.partial";
    public static final String EVENT_TEST = "test";

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);
    static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);
    static final Duration TEST_COOLDOWN = Duration.ofSeconds(10);
    static final int QUEUE_CAPACITY = 16;
    static final int MAX_MESSAGE_LENGTH = 1900;
    static final int MAX_ERROR_LENGTH = 1000;

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ZERO_WIDTH_SPACE = "​";

    record Event(String name, Instant time, String error, List<String> failedServices, String archiveId) {}

    private record Outcome(boolean delivered, Integer status, String error) {}

    private final SettingsRepository settings;
    private final HttpClient client;
    private final Clock clock;
    private final Duration deliveryTimeout;
    private final Duration testTimeout;
    private final ThreadPoolExecutor executor;
    private final AtomicReference<Instant> lastTest = new AtomicReference<>();

    @Autowired
    public WebhookService(SettingsRepository settings) {
        this(settings, HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(), Clock.systemUTC(), DELIVERY_TIMEOUT, TEST_TIMEOUT);
    }

    WebhookService(SettingsRepository settings, HttpClient client, Clock clock, Duration deliveryTimeout, Duration testTimeout) {
        this.settings = settings;
        this.client = client;
        this.clock = clock;
        this.deliveryTimeout = deliveryTimeout;
        this.testTimeout = testTimeout;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
            Thread thread = new Thread(runnable, "webhook-delivery");
            thread.setDaemon(true);
            return thread;
        });
    }

    public WebhookStatusDto status() {
        Optional<String> url = configuredUrl();
        return new WebhookStatusDto(url.isPresent(), url.map(WebhookUrl::target).orElse(null));
    }

    public WebhookStatusDto update(String raw) {
        if (raw == null || raw.isBlank()) {
            settings.saveSetting(FAILURE_WEBHOOK_SETTING, "");
            LOGGER.info("Failure webhook cleared");
            return status();
        }
        String url = WebhookUrl.validate(raw);
        settings.saveSetting(FAILURE_WEBHOOK_SETTING, url);
        LOGGER.info("Failure webhook set, target={}", WebhookUrl.target(url));
        return status();
    }

    public WebhookTestDto test() {
        String url = configuredUrl().orElseThrow(() -> new IllegalArgumentException("No webhook is configured"));
        claimTestSlot();
        Instant now = clock.instant();
        Outcome outcome = deliver(url, new Event(EVENT_TEST, now, null, List.of(), null), testTimeout);
        LOGGER.info("Webhook test to {}: {}", WebhookUrl.host(url), outcome.delivered() ? "delivered" : outcome.error());
        return new WebhookTestDto(outcome.delivered(), outcome.status(), outcome.error());
    }

    public void notifyRun(BackupRunResult result) {
        try {
            String event = FAILED.equals(result.status()) ? EVENT_FAILED : PARTIAL.equals(result.status()) ? EVENT_PARTIAL : null;
            if (event == null) {
                return;
            }
            List<String> failed = result.serviceResults().stream()
                .filter(r -> !COMPLETED.equals(r.status()))
                .map(BackupServiceResult::serviceName)
                .toList();
            String archiveId = result.archive() == null ? null : result.archive().id();
            enqueue(new Event(event, eventTime(result), result.errorMessage(), failed, archiveId));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not queue the failure webhook: {}", e.getClass().getSimpleName());
        }
    }

    private Instant eventTime(BackupRunResult result) {
        try {
            return result.endTime() == null ? clock.instant() : MediaGarrdUtils.parseTime(result.endTime());
        } catch (ParseException | RuntimeException e) {
            return clock.instant();
        }
    }

    public void notifyFailure(String reason) {
        try {
            enqueue(new Event(EVENT_FAILED, clock.instant(), reason, List.of(), null));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not queue the failure webhook: {}", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Optional<String> configuredUrl() {
        return settings.fetchSetting(FAILURE_WEBHOOK_SETTING).filter(url -> !url.isBlank());
    }

    private void claimTestSlot() {
        Instant now = clock.instant();
        while (true) {
            Instant last = lastTest.get();
            if (last != null && now.isBefore(last.plus(TEST_COOLDOWN))) {
                Duration remaining = Duration.between(now, last.plus(TEST_COOLDOWN));
                long seconds = Math.max(1, (remaining.toMillis() + 999) / 1000);
                throw new RateLimitedException("Wait " + seconds + " seconds before sending another test", Duration.ofSeconds(seconds));
            }
            if (lastTest.compareAndSet(last, now)) {
                return;
            }
        }
    }

    private void enqueue(Event event) {
        if (configuredUrl().isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> sendQueued(event));
        } catch (RejectedExecutionException e) {
            LOGGER.warn("Failure webhook dropped, the delivery queue is full or stopped");
        }
    }

    private void sendQueued(Event event) {
        try {
            Optional<String> url = configuredUrl();
            if (url.isEmpty()) {
                return;
            }
            Outcome outcome = deliver(url.get(), event, deliveryTimeout);
            if (outcome.delivered()) {
                LOGGER.info("Failure webhook delivered to {} ({})", WebhookUrl.host(url.get()), event.name());
            } else {
                LOGGER.warn("Failure webhook to {} failed: {}", WebhookUrl.host(url.get()), outcome.error());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Failure webhook failed: {}", e.getClass().getSimpleName());
        }
    }

    private Outcome deliver(String url, Event event, Duration timeout) {
        try {
            URI uri = WebhookUrl.parse(url);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "MediaGarrd-Server")
                .POST(HttpRequest.BodyPublishers.ofString(payload(uri.getHost(), uri.getPath(), event)))
                .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new Outcome(true, status, null);
            }
            return new Outcome(false, status, "Webhook answered " + status);
        } catch (HttpConnectTimeoutException e) {
            return new Outcome(false, null, "Could not connect");
        } catch (HttpTimeoutException e) {
            return new Outcome(false, null, "Timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, null, "Interrupted");
        } catch (IOException e) {
            return new Outcome(false, null, "Could not connect");
        } catch (RuntimeException e) {
            return new Outcome(false, null, "Delivery failed");
        }
    }

    static String payload(String host, String path, Event event) {
        String lowerHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        Map<String, Object> body = new LinkedHashMap<>();
        boolean discord = isHostOrSubdomain(lowerHost, "discord.com") || isHostOrSubdomain(lowerHost, "discordapp.com");
        boolean slackShape = lowerHost.equals("hooks.slack.com") || (discord && path != null && path.toLowerCase(Locale.ROOT).endsWith("/slack"));
        if (discord && !slackShape) {
            body.put("content", cap(message(event, true)));
            body.put("allowed_mentions", Map.of("parse", List.of()));
        } else if (slackShape) {
            body.put("text", cap(escapeSlack(message(event, false))));
        } else {
            String message = cap(message(event, false));
            body.put("event", event.name());
            body.put("time", event.time().toString());
            body.put("error", event.error() == null ? null : sanitize(event.error(), MAX_ERROR_LENGTH));
            body.put("failedServices", event.failedServices().stream().map(name -> sanitize(name, 128)).toList());
            if (event.archiveId() != null) {
                body.put("archiveId", event.archiveId());
            }
            body.put("content", message);
            body.put("text", message);
        }
        return JSON.writeValueAsString(body);
    }

    private static boolean isHostOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String message(Event event, boolean discord) {
        return switch (event.name()) {
            case EVENT_TEST -> "MediaGarrd test notification at " + event.time();
            case EVENT_PARTIAL -> {
                List<String> names = event.failedServices().stream().map(name -> sanitize(name, 128)).toList();
                String detail = names.isEmpty() ? "see the server log" : String.join(", ", names);
                yield "MediaGarrd backup partially failed (" + event.failedServices().size() + " service(s)): " + quote(detail, discord);
            }
            default -> {
                String reason = event.error() == null || event.error().isBlank() ? "see the server log" : sanitize(event.error(), MAX_ERROR_LENGTH);
                yield "MediaGarrd backup failed at " + event.time() + ": " + quote(reason, discord);
            }
        };
    }

    private static String quote(String text, boolean discord) {
        return discord ? "`" + text.replace("`", "'") + "`" : text;
    }

    static String sanitize(String text, int max) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(LogSanitizer.clean(line.strip()));
            }
        }
        String joined = String.join("; ", lines);
        if (joined.length() > max) {
            joined = joined.substring(0, max - 3) + "...";
        }
        return neutralizeMentions(joined);
    }

    private static String neutralizeMentions(String text) {
        return text.replace("@", "@" + ZERO_WIDTH_SPACE).replace("<", "<" + ZERO_WIDTH_SPACE);
    }

    private static String escapeSlack(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String cap(String text) {
        return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
    }
}
