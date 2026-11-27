package wellatleastitried.mediagarrdServer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.json.JsonMapper;
import wellatleastitried.mediagarrdServer.service.SetupService;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RealServletFullContextTest {

    private static final Path DATA_DIR = FullContextSupport.newDataDir();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void seedCompletedSetup() {
        FullContextSupport.openDatabase(DATA_DIR).settings.saveSetting("setup_completed", "2026-11-20 10:00:00");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        FullContextSupport.register(registry, DATA_DIR);
    }

    @LocalServerPort
    int port;

    @Autowired
    SetupService setup;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(10)).build(), BodyHandlers.ofString());
    }

    private static String pairedToken;

    private synchronized String pair() throws Exception {
        if (pairedToken != null) {
            return pairedToken;
        }
        String body = "{\"pin\":\"" + FullContextSupport.currentPin(setup) + "\",\"name\":\"Real servlet\"}";
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/pair"))
            .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body)));
        assertEquals(200, response.statusCode(), response.body());
        pairedToken = (String) JSON.readValue(response.body(), Map.class).get("token");
        return pairedToken;
    }

    @Test
    void aPairedClientCanOpenTheEventStreamAndGetsItsFirstEvent() throws Exception {
        String token = pair();

        HttpResponse<java.io.InputStream> response = client.send(HttpRequest.newBuilder(uri("/api/v1/events"))
            .header("Authorization", "Bearer " + token).header("Accept", "text/event-stream").GET().build(), BodyHandlers.ofInputStream());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
            String line = reader.readLine();
            while (line != null && !line.startsWith("event:")) {
                line = reader.readLine();
            }
            assertEquals("event:state", line);
        }
    }

    @Test
    void theEventStreamRefusesARequestWithoutATokenWithTheErrorShape() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/events")).GET());

        assertEquals(401, response.statusCode());
        assertNotNull(JSON.readValue(response.body(), Map.class).get("error"));
    }

    @Test
    void unknownPathsAndWrongMethodsUseTheErrorShape() throws Exception {
        String token = pair();

        HttpResponse<String> missing = send(HttpRequest.newBuilder(uri("/api/v1/does-not-exist"))
            .header("Authorization", "Bearer " + token).GET());
        HttpResponse<String> wrongMethod = send(HttpRequest.newBuilder(uri("/api/v1/backups"))
            .header("Authorization", "Bearer " + token).POST(BodyPublishers.noBody()));
        HttpResponse<String> staticMissing = send(HttpRequest.newBuilder(uri("/no-such-page.html")).GET());

        assertEquals(404, missing.statusCode());
        assertEquals(Map.of("error", "Not Found"), JSON.readValue(missing.body(), Map.class));
        assertEquals(405, wrongMethod.statusCode());
        assertEquals(Map.of("error", "Method Not Allowed"), JSON.readValue(wrongMethod.body(), Map.class));
        assertTrue(wrongMethod.headers().firstValue("Allow").isPresent());
        assertEquals(404, staticMissing.statusCode());
        assertEquals(Map.of("error", "Not Found"), JSON.readValue(staticMissing.body(), Map.class));
    }

    @Test
    void theErrorDispatchOfTheDisabledSetupPageUsesTheErrorShape() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/config")).GET());

        assertEquals(404, response.statusCode());
        assertEquals(Map.of("error", "Not Found"), JSON.readValue(response.body(), Map.class));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
    }

    @Test
    void realResponsesCarryTheSecurityHeaders() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/health")).GET());

        assertEquals(200, response.statusCode());
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(null));
        assertEquals(SecurityHeadersFilter.CONTENT_SECURITY_POLICY, response.headers().firstValue("Content-Security-Policy").orElse(null));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(null));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
    }

    @Test
    void aChunkedBodyOverTheCapIsRefusedAndASmallOneIsNot() throws Exception {
        byte[] big = ("{\"pin\":\"" + "1".repeat(RequestBodyLimitFilter.MAX_BODY_BYTES + 10) + "\"}").getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> refused = send(HttpRequest.newBuilder(uri("/api/v1/pair"))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big)))));
        HttpResponse<String> accepted = send(HttpRequest.newBuilder(uri("/api/v1/pair"))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream("{\"pin\":\"000000\"}".getBytes(StandardCharsets.UTF_8))))));

        assertEquals(413, refused.statusCode());
        assertEquals(Map.of("error", RequestBodyLimitFilter.MESSAGE), JSON.readValue(refused.body(), Map.class));
        assertTrue(accepted.statusCode() != 413, accepted.body());
    }

    @Test
    void anUnknownCharsetInTheContentTypeIsRefusedWith415AndTheErrorShape() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/pair"))
            .header("Content-Type", "application/json; charset=nope-8")
            .POST(BodyPublishers.ofString("{\"pin\":\"000000\"}")));

        assertEquals(415, response.statusCode(), response.body());
        assertTrue(JSON.readValue(response.body(), Map.class).containsKey("error"), response.body());
    }
}
