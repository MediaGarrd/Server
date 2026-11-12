package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.dto.CheckState;
import wellatleastitried.mediagarrdServer.dto.VersionStatusDto;

class UpdateCheckServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T18:00:00Z");

    private final List<String> requests = new ArrayList<>();
    private final List<String> userAgents = new ArrayList<>();
    private final MediaGarrdProperties properties = new MediaGarrdProperties();
    private HttpServer server;
    private int status = 200;
    private String rateLimitRemaining;
    private String response = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        properties.setGithubApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        properties.setUpdateRepository("MediaGarrd/Server");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private UpdateCheckService service(String currentVersion) {
        return new UpdateCheckService(properties, currentVersion, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String release(String tag) {
        return "{\"tag_name\": \"" + tag + "\", \"html_url\": \"https://github.com/MediaGarrd/Server/releases/tag/" + tag
            + "\", \"draft\": false, \"prerelease\": false, \"id\": 1}";
    }

    @Test
    void reportsNotCheckedBeforeTheFirstRun() {
        VersionStatusDto result = service("0.1.0").status();

        assertEquals(new VersionStatusDto("0.1.0", null, null, false, CheckState.NOT_CHECKED, null, null), result);
        assertTrue(requests.isEmpty());
    }

    @Test
    void reportsAnUpdateWhenTheLatestReleaseIsNewer() {
        response = release("v0.2.0");
        UpdateCheckService service = service("0.1.0");

        service.check();

        assertEquals(new VersionStatusDto("0.1.0", "0.2.0", "https://github.com/MediaGarrd/Server/releases/tag/v0.2.0", true, CheckState.OK, NOW, null), service.status());
        assertEquals(List.of("/repos/MediaGarrd/Server/releases/latest"), requests);
        assertEquals(List.of("MediaGarrd-Server/0.1.0"), userAgents);
    }

    @Test
    void reportsUpToDateWhenTheLatestReleaseIsTheSameVersion() {
        response = release("v0.1.0");
        UpdateCheckService service = service("0.1.0");

        service.check();

        assertEquals(CheckState.OK, service.status().checkState());
        assertEquals("0.1.0", service.status().latestVersion());
        assertFalse(service.status().updateAvailable());
    }

    @Test
    void neverReportsAnOlderReleaseAsAnUpdate() {
        response = release("v0.1.0");
        UpdateCheckService service = service("0.2.0");

        service.check();

        assertEquals(CheckState.OK, service.status().checkState());
        assertFalse(service.status().updateAvailable());
    }

    @Test
    void treatsAPrereleaseOfTheRunningVersionAsOlder() {
        response = release("v0.2.0-rc.1");
        UpdateCheckService service = service("0.2.0");

        service.check();

        assertFalse(service.status().updateAvailable());
    }

    @Test
    void reportsNoReleasesOnNotFound() {
        status = 404;
        response = "{\"message\": \"Not Found\"}";
        UpdateCheckService service = service("0.1.0");

        service.check();

        assertEquals(new VersionStatusDto("0.1.0", null, null, false, CheckState.NO_RELEASES, NOW, null), service.status());
    }

    @Test
    void reportsRateLimitingAsAnError() {
        status = 403;
        rateLimitRemaining = "0";
        UpdateCheckService service = service("0.1.0");

        service.check();

        assertEquals(CheckState.ERROR, service.status().checkState());
        assertEquals("GitHub rate limit reached", service.status().message());
        assertFalse(service.status().updateAvailable());

        status = 429;
        rateLimitRemaining = null;
        service.check();
        assertEquals("GitHub rate limit reached", service.status().message());
    }

    @Test
    void reportsAForbiddenResponseWithoutAnExhaustedLimitAsAPlainHttpError() {
        status = 403;
        UpdateCheckService service = service("0.1.0");

        service.check();
        assertEquals(CheckState.ERROR, service.status().checkState());
        assertEquals("GitHub returned HTTP 403", service.status().message());

        rateLimitRemaining = "12";
        service.check();
        assertEquals("GitHub returned HTTP 403", service.status().message());
    }

    @Test
    void rejectsCheckIntervalsBelowOneHour() {
        properties.setUpdateCheckInterval(Duration.ofMinutes(59));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> service("0.1.0"));
        assertTrue(failure.getMessage().contains("at least PT1H"));

        properties.setUpdateCheckInterval(Duration.ZERO);
        assertThrows(IllegalStateException.class, () -> service("0.1.0"));

        properties.setUpdateCheckInterval(Duration.ofHours(1));
        service("0.1.0");
    }

    @Test
    void reportsServerErrorsAsAnError() {
        status = 500;
        UpdateCheckService service = service("0.1.0");

        service.check();

        assertEquals(CheckState.ERROR, service.status().checkState());
        assertEquals("GitHub returned HTTP 500", service.status().message());
        assertEquals(NOW, service.status().checkedAt());
    }

    @Test
    void reportsAnUnreachableGithubAsAnErrorWithoutThrowing() {
        server.stop(0);
        UpdateCheckService service = service("0.1.0");

        service.check();

        assertEquals(CheckState.ERROR, service.status().checkState());
        assertEquals("GitHub is unreachable", service.status().message());
    }

    @Test
    void reportsMalformedResponsesAsAnError() {
        response = "<html>not json</html>";
        UpdateCheckService service = service("0.1.0");

        service.check();

        assertEquals(CheckState.ERROR, service.status().checkState());
        assertFalse(service.status().updateAvailable());
    }

    @Test
    void reportsAnUnrecognizedTagAsAnError() {
        response = release("nightly");
        UpdateCheckService service = service("0.1.0");

        service.check();

        assertEquals(CheckState.ERROR, service.status().checkState());
        assertEquals("Latest release has an unrecognized version tag", service.status().message());
    }

    @Test
    void reportsAnErrorWithoutCallingGithubWhenTheCurrentVersionIsUnknown() {
        response = release("v9.9.9");

        for (String unknown : new String[] {null, "unspecified"}) {
            UpdateCheckService service = service(unknown);
            service.check();
            assertEquals(CheckState.ERROR, service.status().checkState());
            assertFalse(service.status().updateAvailable());
            assertEquals("Current version is unknown", service.status().message());
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    void buildsTheReleaseUrlWhenGithubOmitsIt() {
        response = "{\"tag_name\": \"v0.3.0\"}";
        UpdateCheckService service = service("0.1.0");

        service.check();

        assertEquals("https://github.com/MediaGarrd/Server/releases/tag/v0.3.0", service.status().releaseUrl());
    }

    @Test
    void recoversOnTheNextCheckAfterAFailure() {
        status = 500;
        UpdateCheckService service = service("0.1.0");
        service.check();
        assertEquals(CheckState.ERROR, service.status().checkState());

        status = 200;
        response = release("v0.2.0");
        service.check();

        assertEquals(CheckState.OK, service.status().checkState());
        assertTrue(service.status().updateAvailable());
        assertNull(service.status().message());
    }

    @Test
    void usesTheConfiguredRepository() {
        properties.setUpdateRepository("someone/else");
        response = release("v0.2.0");
        UpdateCheckService service = service("0.1.0");

        service.check();

        assertEquals(List.of("/repos/someone/else/releases/latest"), requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestURI().getPath());
        userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (rateLimitRemaining != null) {
            exchange.getResponseHeaders().add("X-RateLimit-Remaining", rateLimitRemaining);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
