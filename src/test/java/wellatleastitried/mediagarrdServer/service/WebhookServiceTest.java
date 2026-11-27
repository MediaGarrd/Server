package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.FAILURE_WEBHOOK_SETTING;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.COMPLETED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.FAILED;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.Status.PARTIAL;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import wellatleastitried.mediagarrdServer.dto.WebhookStatusDto;
import wellatleastitried.mediagarrdServer.dto.WebhookTestDto;
import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.model.BackupServiceResult;
import wellatleastitried.mediagarrdServer.repository.SettingsRepository;

@Timeout(30)
class WebhookServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final String SECRET = "SECRETTOKEN123";

    private record Received(String method, String path, String contentType, String body) {}

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> stored = new AtomicReference<>("");
    private final SettingsRepository settings = mock(SettingsRepository.class);
    private final MutableClock clock = new MutableClock();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private HttpServer server;
    private WebhookService service;
    private volatile int status = 200;
    private volatile String location;
    private volatile long delayMs;
    private volatile CountDownLatch gate;
    private volatile CountDownLatch arrived = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        when(settings.fetchSetting(FAILURE_WEBHOOK_SETTING)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return null;
        }).when(settings).saveSetting(anyString(), anyString());
        service = newService(Duration.ofMillis(800), Duration.ofMillis(800));
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        if (gate != null) {
            gate.countDown();
        }
        service.shutdown();
        server.stop(0);
    }

    private WebhookService newService(Duration delivery, Duration test) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        return new WebhookService(settings, client, clock, delivery, test);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("Content-Type"), body));
        arrived.countDown();
        try {
            if (gate != null) {
                gate.await(10, TimeUnit.SECONDS);
            }
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (location != null) {
            exchange.getResponseHeaders().add("Location", location);
        }
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private String logText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    private void awaitRequest() throws InterruptedException {
        assertTrue(arrived.await(5, TimeUnit.SECONDS), "the receiver was never called");
    }

    private static BackupRunResult run(String status, BackupArchive archive, String error, String... failedNames) {
        List<BackupServiceResult> results = new ArrayList<>();
        results.add(new BackupServiceResult("id", COMPLETED, "t", "t", "radarr", null));
        for (String name : failedNames) {
            results.add(new BackupServiceResult("id", FAILED, "t", "t", name, "boom"));
        }
        return new BackupRunResult("id", "s", "e", status, results, archive, error);
    }

    private static final BackupArchive ARCHIVE = new BackupArchive("archive-1", "archive-1.zip", Path.of("archive-1.zip"), 1, Instant.EPOCH);

    @Test
    void acceptsHttpAndHttpsUrlsAndKeepsOnlyTheTarget() {
        WebhookStatusDto discord = service.update("  https://Discord.com/api/webhooks/123/" + SECRET + "?wait=true  ");
        assertEquals(new WebhookStatusDto(true, "https://discord.com"), discord);
        assertEquals("https://Discord.com/api/webhooks/123/" + SECRET + "?wait=true", stored.get());

        assertEquals(new WebhookStatusDto(true, "http://192.168.1.5:8123"), service.update("http://192.168.1.5:8123/api/webhook/" + SECRET));
        assertEquals(new WebhookStatusDto(true, "http://[::1]:9000"), service.update("http://[::1]:9000/x"));
    }

    @Test
    void rejectsUrlsThatAreNotAbsoluteHttpWithAFixedMessage() {
        String[][] cases = {
            {"ftp://example.com/hook", "Webhook URL must use http or https"},
            {"example.com/hook", "Webhook URL must use http or https"},
            {"/api/webhooks/1/" + SECRET, "Webhook URL must use http or https"},
            {"https://user:" + SECRET + "@example.com/hook", "Webhook URL must not contain user info"},
            {"https://example.com/hook#" + SECRET, "Webhook URL must not contain a fragment"},
            {"https://example.com/ho ok/" + SECRET, "Webhook URL must not contain spaces or control characters"},
            {"https://example.com/hook\n" + SECRET, "Webhook URL must not contain spaces or control characters"},
            {"https://example.com/" + "a".repeat(2049), "Webhook URL is too long"},
            {"http://home_assistant:8123/api/webhook/" + SECRET, "Webhook URL must have a valid host name"},
            {"http:///" + SECRET, "Webhook URL must have a valid host name"},
            {"https://discord.com/api/webhooks/1/SECRET|TOKEN", "Webhook URL is not a valid URL"},
            {"https://discord.com/api/webhooks/1/%zz" + SECRET, "Webhook URL is not a valid URL"},
        };
        for (String[] testCase : cases) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.update(testCase[0]), testCase[0]);
            assertEquals(testCase[1], e.getMessage(), testCase[0]);
            assertFalse(e.getMessage().contains("SECRET"));
        }
        assertEquals("", stored.get());
    }

    @Test
    void anUrlOfExactlyTheLimitIsAccepted() {
        String url = "https://example.com/" + "a".repeat(2048 - "https://example.com/".length());

        assertEquals(2048, url.length());
        assertTrue(service.update(url).configured());
    }

    @Test
    void rejectedAndAcceptedUrlsNeverReachTheLogs() {
        assertThrows(IllegalArgumentException.class, () -> service.update("https://discord.com/api/webhooks/1/SECRET|TOKEN" + SECRET));
        service.update("https://discord.com/api/webhooks/1/" + SECRET);
        service.test();

        assertFalse(logText().contains(SECRET));
        assertFalse(logText().contains("/api/webhooks"));
    }

    @Test
    void emptyNullOrBlankClearsTheWebhook() {
        service.update(url("/hook"));
        assertTrue(service.status().configured());

        assertEquals(new WebhookStatusDto(false, null), service.update(""));
        service.update(url("/hook"));
        assertEquals(new WebhookStatusDto(false, null), service.update(null));
        service.update(url("/hook"));
        assertEquals(new WebhookStatusDto(false, null), service.update("   "));
    }

    @Test
    void statusIsNotConfiguredWhenNothingIsStored() {
        assertEquals(new WebhookStatusDto(false, null), service.status());
        stored.set(null);
        assertEquals(new WebhookStatusDto(false, null), service.status());
    }

    @Test
    void genericPayloadForAPartialRun() {
        String json = WebhookService.payload("n8n.local", "/hook", new WebhookService.Event(
            WebhookService.EVENT_PARTIAL, NOW, "sonarr: boom\nlidarr: nope", List.of("sonarr", "lidarr"), "archive-1"));

        assertEquals("{\"event\":\"backup.partial\",\"time\":\"2026-11-19T10:00:00Z\",\"error\":\"sonarr: boom; lidarr: nope\","
            + "\"failedServices\":[\"sonarr\",\"lidarr\"],\"archiveId\":\"archive-1\","
            + "\"content\":\"MediaGarrd backup partially failed (2 service(s)): sonarr, lidarr\","
            + "\"text\":\"MediaGarrd backup partially failed (2 service(s)): sonarr, lidarr\"}", json);
    }

    @Test
    void genericPayloadForAFailedRunHasNoArchiveId() {
        String json = WebhookService.payload("ntfy.local", "/hook", new WebhookService.Event(
            WebhookService.EVENT_FAILED, NOW, "radarr: boom", List.of("radarr"), null));

        assertEquals("{\"event\":\"backup.failed\",\"time\":\"2026-11-19T10:00:00Z\",\"error\":\"radarr: boom\","
            + "\"failedServices\":[\"radarr\"],"
            + "\"content\":\"MediaGarrd backup failed at 2026-11-19T10:00:00Z: radarr: boom\","
            + "\"text\":\"MediaGarrd backup failed at 2026-11-19T10:00:00Z: radarr: boom\"}", json);
    }

    @Test
    void genericPayloadForATest() {
        String json = WebhookService.payload("n8n.local", "/hook", new WebhookService.Event(WebhookService.EVENT_TEST, NOW, null, List.of(), null));

        assertEquals("{\"event\":\"test\",\"time\":\"2026-11-19T10:00:00Z\",\"error\":null,\"failedServices\":[],"
            + "\"content\":\"MediaGarrd test notification at 2026-11-19T10:00:00Z\","
            + "\"text\":\"MediaGarrd test notification at 2026-11-19T10:00:00Z\"}", json);
    }

    @Test
    void discordHostsGetContentAndNoMentionsOnly() {
        WebhookService.Event event = new WebhookService.Event(WebhookService.EVENT_FAILED, NOW, "radarr: `boom`", List.of("radarr"), null);
        String expected = "{\"content\":\"MediaGarrd backup failed at 2026-11-19T10:00:00Z: `radarr: 'boom'`\",\"allowed_mentions\":{\"parse\":[]}}";

        for (String host : new String[] {"discord.com", "DISCORD.com", "discordapp.com", "canary.discord.com", "ptb.discord.com"}) {
            assertEquals(expected, WebhookService.payload(host, "/hook", event), host);
        }
        assertFalse(WebhookService.payload("notdiscord.com", "/hook", event).contains("allowed_mentions"));
        assertFalse(WebhookService.payload("discord.com.evil.example", "/hook", event).contains("allowed_mentions"));
    }

    @Test
    void slackHostsGetEscapedTextOnly() {
        String json = WebhookService.payload("hooks.slack.com", "/hook", new WebhookService.Event(
            WebhookService.EVENT_FAILED, NOW, "a & b <c> d", List.of(), null));

        assertEquals("{\"text\":\"MediaGarrd backup failed at 2026-11-19T10:00:00Z: a &amp; b &lt;​c&gt; d\"}", json);
    }

    @Test
    void mentionsAreNeutralisedInEveryShape() {
        WebhookService.Event event = new WebhookService.Event(WebhookService.EVENT_FAILED, NOW, "@everyone @here <@123> <#456> <!here>", List.of("@everyone"), null);

        for (String host : new String[] {"discord.com", "hooks.slack.com", "example.com"}) {
            String json = WebhookService.payload(host, "/hook", event);
            assertFalse(json.contains("@everyone"), host);
            assertFalse(json.contains("@here"), host);
            assertFalse(json.contains("<@"), host);
            assertFalse(json.contains("<#"), host);
            assertFalse(json.contains("<!"), host);
        }
    }

    @Test
    void theMessageIsCappedBelowDiscordsLimitInEveryShape() {
        String many = "x".repeat(5000);
        WebhookService.Event event = new WebhookService.Event(WebhookService.EVENT_FAILED, NOW, many, List.of(), null);

        assertTrue(WebhookService.payload("discord.com", "/hook", event).length() < 2000);
        assertTrue(WebhookService.payload("hooks.slack.com", "/hook", event).length() < 2000);
        String generic = WebhookService.payload("example.com", "/hook", event);
        assertTrue(generic.contains("\"content\":\"MediaGarrd backup failed at"));
        assertTrue(generic.length() < 3200);
        assertTrue(WebhookService.payload("hooks.slack.com", "/hook", new WebhookService.Event(WebhookService.EVENT_FAILED, NOW, "&".repeat(1500), List.of(), null)).length() < 2000);
    }

    @Test
    void lineBreaksInTheErrorAreCollapsed() {
        String json = WebhookService.payload("example.com", "/hook", new WebhookService.Event(WebhookService.EVENT_FAILED, NOW, "one\r\n\r\ntwo three", List.of(), null));

        assertTrue(json.contains("\"error\":\"one; two; three\""), json);
    }

    @Test
    void aFailedRunIsPostedAsJson() throws Exception {
        service.update(url("/hooks/" + SECRET));

        service.notifyRun(run(FAILED, null, "radarr: boom", "radarr"));
        awaitRequest();

        Received request = received.get(0);
        assertEquals("POST", request.method());
        assertEquals("/hooks/" + SECRET, request.path());
        assertEquals("application/json", request.contentType());
        assertTrue(request.body().contains("\"event\":\"backup.failed\""));
        assertTrue(request.body().contains("\"failedServices\":[\"radarr\"]"));
        assertFalse(request.body().contains("archiveId"));
    }

    @Test
    void aPartialRunIsPostedWithItsArchive() throws Exception {
        service.update(url("/hook"));

        service.notifyRun(run(PARTIAL, ARCHIVE, "sonarr: boom", "sonarr"));
        awaitRequest();

        assertTrue(received.get(0).body().contains("\"event\":\"backup.partial\""));
        assertTrue(received.get(0).body().contains("\"archiveId\":\"archive-1\""));
        assertTrue(received.get(0).body().contains("\"failedServices\":[\"sonarr\"]"));
    }

    @Test
    void aCompletedRunSendsNothing() throws Exception {
        service.update(url("/hook"));

        service.notifyRun(run(COMPLETED, ARCHIVE, ""));

        assertFalse(arrived.await(300, TimeUnit.MILLISECONDS));
        assertTrue(received.isEmpty());
    }

    @Test
    void nothingIsSentWithoutAWebhookOrAfterItIsCleared() throws Exception {
        service.notifyRun(run(FAILED, null, "boom", "radarr"));
        service.update(url("/hook"));
        service.update("");
        service.notifyFailure("disk full");

        assertFalse(arrived.await(300, TimeUnit.MILLISECONDS));
        assertTrue(received.isEmpty());
    }

    @Test
    void aFailureReasonIsSentAsAFailedEvent() throws Exception {
        service.update(url("/hook"));

        service.notifyFailure("disk full");
        awaitRequest();

        assertTrue(received.get(0).body().contains("\"event\":\"backup.failed\""));
        assertTrue(received.get(0).body().contains("\"error\":\"disk full\""));
        assertTrue(received.get(0).body().contains("\"failedServices\":[]"));
    }

    @Test
    void aNullFailureReasonStillSends() throws Exception {
        service.update(url("/hook"));

        service.notifyFailure(null);
        awaitRequest();

        assertTrue(received.get(0).body().contains("see the server log"));
    }

    @Test
    void aTestDeliversToTheSavedUrl() {
        service.update(url("/hook"));

        WebhookTestDto result = service.test();

        assertEquals(new WebhookTestDto(true, 200, null), result);
        assertEquals(1, received.size());
        assertTrue(received.get(0).body().contains("\"event\":\"test\""));
    }

    @Test
    void aTestWithoutAWebhookIsRejectedAndDoesNotUseTheCooldown() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, service::test);
        assertEquals("No webhook is configured", e.getMessage());

        service.update(url("/hook"));
        assertTrue(service.test().delivered());
    }

    @Test
    void aTestReportsTheStatusWithoutTheBody() {
        service.update(url("/missing/" + SECRET));
        status = 404;

        WebhookTestDto result = service.test();

        assertEquals(new WebhookTestDto(false, 404, "Webhook answered 404"), result);
    }

    @Test
    void redirectsAreNotFollowed() {
        service.update(url("/hook"));
        status = 302;
        location = url("/elsewhere");

        WebhookTestDto result = service.test();

        assertEquals(new WebhookTestDto(false, 302, "Webhook answered 302"), result);
        assertEquals(1, received.size());
        assertEquals("/hook", received.get(0).path());
    }

    @Test
    void aSlowReceiverTimesOutTheTest() {
        service.update(url("/hook"));
        delayMs = 3000;

        long started = System.nanoTime();
        WebhookTestDto result = service.test();

        assertEquals(new WebhookTestDto(false, null, "Timed out"), result);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2500);
    }

    @Test
    void anUnreachableReceiverIsReportedWithoutTheUrl() {
        service.update(url("/hook/" + SECRET));
        server.stop(0);

        WebhookTestDto result = service.test();

        assertEquals(new WebhookTestDto(false, null, "Could not connect"), result);
        assertFalse(logText().contains(SECRET));
    }

    @Test
    void aSlowReceiverNeverBlocksTheCaller() throws Exception {
        service.update(url("/hook"));
        delayMs = 3000;

        long started = System.nanoTime();
        service.notifyRun(run(FAILED, null, "boom", "radarr"));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(elapsedMs < 500, "notify took " + elapsedMs + " ms");
        awaitRequest();
    }

    @Test
    void aFailedDeliveryIsLoggedAsAWarningWithTheHostOnly() throws Exception {
        service.update(url("/hook/" + SECRET + "?token=" + SECRET));
        status = 500;

        service.notifyFailure("disk full");
        awaitRequest();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!logText().contains("Failure webhook to") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        String text = logText();
        assertTrue(text.contains("Failure webhook to 127.0.0.1 failed: Webhook answered 500"), text);
        assertFalse(text.contains(SECRET));
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel().toString().equals("WARN") && event.getFormattedMessage().contains("Failure webhook to")));
    }

    @Test
    void anUnreachableReceiverNeverThrowsIntoTheCaller() throws Exception {
        service.update(url("/hook"));
        server.stop(0);

        service.notifyRun(run(FAILED, null, "boom", "radarr"));
        service.notifyFailure("disk full");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (logs.list.stream().filter(event -> event.getFormattedMessage().contains("Failure webhook to")).count() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(2, logs.list.stream().filter(event -> event.getFormattedMessage().contains("Failure webhook to 127.0.0.1 failed: Could not connect")).count());
    }

    @Test
    void aFullQueueDropsEventsWithAWarningAndNeverThrows() throws Exception {
        service.update(url("/hook"));
        gate = new CountDownLatch(1);

        for (int i = 0; i < WebhookService.QUEUE_CAPACITY + 5; i++) {
            service.notifyFailure("failure " + i);
        }

        assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains("dropped")));
        gate.countDown();
    }

    @Test
    void anEventIsNotSentAfterShutdown() throws Exception {
        service.update(url("/hook"));
        service.shutdown();

        service.notifyFailure("disk full");

        assertFalse(arrived.await(300, TimeUnit.MILLISECONDS));
        assertTrue(logText().contains("dropped"));
    }

    @Test
    void testsAreLimitedToOneEveryTenSeconds() {
        service.update(url("/hook"));
        assertTrue(service.test().delivered());

        RateLimitedException e = assertThrows(RateLimitedException.class, service::test);
        assertEquals(Duration.ofSeconds(10), e.retryAfter());
        assertEquals("Wait 10 seconds before sending another test", e.getMessage());

        clock.advance(Duration.ofSeconds(4));
        RateLimitedException later = assertThrows(RateLimitedException.class, service::test);
        assertEquals(Duration.ofSeconds(6), later.retryAfter());
        assertEquals(1, received.size());

        clock.advance(Duration.ofSeconds(6));
        assertTrue(service.test().delivered());
        assertEquals(2, received.size());
    }

    @Test
    void aFailedTestStillCountsAgainstTheCooldown() {
        service.update(url("/hook"));
        status = 500;
        assertFalse(service.test().delivered());

        assertThrows(RateLimitedException.class, service::test);
    }

    @Test
    void concurrentTestsOnlyLetOneThrough() throws Exception {
        service.update(url("/hook"));
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        List<Object> outcomes = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    go.await();
                    outcomes.add(service.test());
                } catch (RateLimitedException e) {
                    outcomes.add(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            workers.add(worker);
            worker.start();
        }
        go.countDown();
        for (Thread worker : workers) {
            worker.join(10000);
        }

        assertEquals(threads, outcomes.size());
        assertEquals(1, outcomes.stream().filter(WebhookTestDto.class::isInstance).count());
    }

    @Test
    void aFailedRunThatHasAnArchiveIncludesItsId() throws Exception {
        service.update(url("/hook"));

        service.notifyRun(run(FAILED, ARCHIVE, "radarr: boom", "radarr"));
        awaitRequest();

        assertTrue(received.get(0).body().contains("\"event\":\"backup.failed\""));
        assertTrue(received.get(0).body().contains("\"archiveId\":\"archive-1\""));
    }

    @Test
    void theEventTimeIsTheRecordsEndTime() throws Exception {
        service.update(url("/hook"));
        BackupRunResult result = new BackupRunResult("id", "2026-11-19 09:00:00", "2026-11-19 09:30:00", FAILED,
            List.of(new BackupServiceResult("id", FAILED, "t", "t", "radarr", "boom")), null, "radarr: boom");

        service.notifyRun(result);
        awaitRequest();

        String expected = wellatleastitried.mediagarrdServer.utilities.MediaGarrdUtils.parseTime("2026-11-19 09:30:00").toString();
        assertTrue(received.get(0).body().contains("\"time\":\"" + expected + "\""), received.get(0).body());
    }

    @Test
    void aDiscordSlackCompatibleUrlGetsTheSlackShape() {
        WebhookService.Event event = new WebhookService.Event(WebhookService.EVENT_FAILED, NOW, "a & b", List.of(), null);

        assertEquals("{\"text\":\"MediaGarrd backup failed at 2026-11-19T10:00:00Z: a &amp; b\"}",
            WebhookService.payload("discord.com", "/api/webhooks/1/abc/slack", event));
        assertEquals("{\"text\":\"MediaGarrd backup failed at 2026-11-19T10:00:00Z: a &amp; b\"}",
            WebhookService.payload("discordapp.com", "/api/webhooks/1/abc/SLACK", event));
        assertTrue(WebhookService.payload("discord.com", "/api/webhooks/1/abc", event).contains("allowed_mentions"));
        assertTrue(WebhookService.payload("example.com", "/x/slack", event).contains("\"event\":\"backup.failed\""));
    }

    @Test
    void aThrowingSettingsReadNeverReachesTheCallerAndIsLogged() {
        when(settings.fetchSetting(FAILURE_WEBHOOK_SETTING)).thenThrow(new IllegalStateException("database locked " + SECRET));

        service.notifyRun(run(FAILED, null, "boom", "radarr"));
        service.notifyFailure("disk full");

        assertEquals(2, logs.list.stream().filter(event -> event.getLevel().toString().equals("WARN") && event.getFormattedMessage().contains("Could not queue")).count());
        assertFalse(logText().contains(SECRET));
    }

    @Test
    void aStoredValueWithoutAHostIsConfiguredWithNoTarget() {
        stored.set("http:///only-a-path");
        assertEquals(new WebhookStatusDto(true, null), service.status());
        stored.set("not a url");
        assertEquals(new WebhookStatusDto(true, null), service.status());
    }

    @Test
    void theUpdateRequestDoesNotPrintTheUrl() {
        assertFalse(new wellatleastitried.mediagarrdServer.dto.WebhookUpdateRequest("https://discord.com/api/webhooks/1/" + SECRET).toString().contains(SECRET));
    }
}
