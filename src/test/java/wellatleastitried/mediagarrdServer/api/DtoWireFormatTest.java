package wellatleastitried.mediagarrdServer.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import wellatleastitried.mediagarrdServer.dto.BackupDto;
import wellatleastitried.mediagarrdServer.dto.BackupRunDto;
import wellatleastitried.mediagarrdServer.dto.CheckState;
import wellatleastitried.mediagarrdServer.dto.ConfigUpdateRequest;
import wellatleastitried.mediagarrdServer.dto.PairRequest;
import wellatleastitried.mediagarrdServer.dto.PairResponse;
import wellatleastitried.mediagarrdServer.dto.PairedClientDto;
import wellatleastitried.mediagarrdServer.dto.PairingCodeDto;
import wellatleastitried.mediagarrdServer.dto.PublicHealthDto;
import wellatleastitried.mediagarrdServer.dto.ScheduleUpdateRequest;
import wellatleastitried.mediagarrdServer.dto.ServerConfigDto;
import wellatleastitried.mediagarrdServer.dto.ServerStatusDto;
import wellatleastitried.mediagarrdServer.dto.SetupCompleteDto;
import wellatleastitried.mediagarrdServer.dto.SetupSessionRequest;
import wellatleastitried.mediagarrdServer.dto.SetupStatusDto;
import wellatleastitried.mediagarrdServer.dto.VersionStatusDto;
import wellatleastitried.mediagarrdServer.model.PairedClient;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;
import wellatleastitried.mediagarrdServer.service.PairingService;
import wellatleastitried.mediagarrdServer.service.SetupService;

@WebMvcTest(DtoWireFormatTest.WireController.class)
@Import(DtoWireFormatTest.WireController.class)
class DtoWireFormatTest {

    private static final Instant MILLIS = Instant.parse("2026-11-11T18:00:00.123Z");
    private static final Instant WHOLE = Instant.parse("2026-11-11T18:00:00Z");
    private static final Instant NANOS = Instant.parse("2026-11-11T18:00:00.123456789Z");

    @RestController
    static class WireController {

        @GetMapping("/wire/backup")
        BackupDto backup() {
            return new BackupDto("id-1", "backup-1.zip", 1234567890123L, MILLIS);
        }

        @GetMapping("/wire/backup-nanos")
        BackupDto backupNanos() {
            return new BackupDto("id-1", "backup-1.zip", 1L, NANOS);
        }

        @GetMapping("/wire/backup-whole")
        BackupDto backupWhole() {
            return new BackupDto("id-1", "backup-1.zip", 1L, WHOLE);
        }

        @GetMapping("/wire/backup-run")
        BackupRunDto backupRun() {
            return new BackupRunDto("run-1", "2026-11-11T18:00:00Z", "2026-11-11T18:05:00Z", List.of("a", "b"),
                new BackupDto("id-1", "backup-1.zip", 10L, WHOLE));
        }

        @GetMapping("/wire/backup-run-empty")
        BackupRunDto backupRunEmpty() {
            return new BackupRunDto("run-1", "s", "e", List.of(), null);
        }

        @GetMapping("/wire/paired-client")
        PairedClientDto pairedClient() {
            return new PairedClientDto("c-1", "Living room", WHOLE, null, true);
        }

        @GetMapping("/wire/paired-client-used")
        PairedClientDto pairedClientUsed() {
            return new PairedClientDto("c-1", "Living room", WHOLE, MILLIS, false);
        }

        @GetMapping("/wire/pairing-code")
        PairingCodeDto pairingCode() {
            return new PairingCodeDto("ABCD-EFGH", WHOLE);
        }

        @GetMapping("/wire/pair-response")
        PairResponse pairResponse() {
            return new PairResponse("tok", "c-1", "Living room");
        }

        @GetMapping("/wire/public-health")
        PublicHealthDto publicHealth() {
            return new PublicHealthDto(true, Duration.ofHours(12));
        }

        @GetMapping("/wire/server-config")
        ServerConfigDto serverConfig() {
            return new ServerConfigDto("/var/lib/mediagarrd/backups", 10, Duration.ofMinutes(90));
        }

        @GetMapping("/wire/server-status")
        ServerStatusDto serverStatus() {
            return new ServerStatusDto(true, false, 10, Duration.ofSeconds(3661));
        }

        @GetMapping("/wire/setup-status")
        SetupStatusDto setupStatus() {
            return new SetupStatusDto(false, true);
        }

        @GetMapping("/wire/setup-complete")
        SetupCompleteDto setupComplete() {
            return new SetupCompleteDto("123456");
        }

        @GetMapping("/wire/setup-complete-no-pin")
        SetupCompleteDto setupCompleteNoPin() {
            return new SetupCompleteDto(null);
        }

        @GetMapping("/wire/version-status")
        VersionStatusDto versionStatus() {
            return new VersionStatusDto("0.1.0", "0.2.0", "https://example.invalid/r", true, CheckState.OK, MILLIS, null);
        }

        @GetMapping("/wire/version-status-error")
        VersionStatusDto versionStatusError() {
            return new VersionStatusDto("0.1.0", null, null, false, CheckState.NO_RELEASES, WHOLE, "none");
        }

        @GetMapping("/wire/paired-client-model")
        PairedClient pairedClientModel() {
            return new PairedClient("c-1", "Living room", WHOLE, MILLIS);
        }

        @GetMapping("/wire/service-definition")
        ServiceDefinition serviceDefinition() {
            return new ServiceDefinition("plex", List.of("/a", "/b"));
        }

        @PostMapping("/wire/echo/config-update")
        ConfigUpdateRequest echoConfigUpdate(@RequestBody ConfigUpdateRequest r) {
            return r;
        }

        @PostMapping("/wire/echo/pair-request")
        PairRequest echoPairRequest(@RequestBody PairRequest r) {
            return r;
        }

        @PostMapping("/wire/echo/schedule-update")
        ScheduleUpdateRequest echoScheduleUpdate(@RequestBody ScheduleUpdateRequest r) {
            return r;
        }

        @PostMapping("/wire/echo/setup-session")
        SetupSessionRequest echoSetupSession(@RequestBody SetupSessionRequest r) {
            return r;
        }

        @PostMapping("/wire/echo/service-definition")
        ServiceDefinition echoServiceDefinition(@RequestBody ServiceDefinition r) {
            return r;
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SetupService setup;

    @MockitoBean
    PairingService pairing;

    private void assertWire(String path, String expected) throws Exception {
        mockMvc.perform(get(path))
            .andExpect(status().isOk())
            .andExpect(content().string(expected));
    }

    private void assertEcho(String path, String request, String expected) throws Exception {
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk())
            .andExpect(content().string(expected));
    }

    @Test
    void backupDtoWritesInstantsAsIsoStrings() throws Exception {
        assertWire("/wire/backup",
            "{\"id\":\"id-1\",\"fileName\":\"backup-1.zip\",\"sizeBytes\":1234567890123,\"createdAt\":\"2026-11-11T18:00:00.123Z\"}");
        assertWire("/wire/backup-whole",
            "{\"id\":\"id-1\",\"fileName\":\"backup-1.zip\",\"sizeBytes\":1,\"createdAt\":\"2026-11-11T18:00:00Z\"}");
        assertWire("/wire/backup-nanos",
            "{\"id\":\"id-1\",\"fileName\":\"backup-1.zip\",\"sizeBytes\":1,\"createdAt\":\"2026-11-11T18:00:00.123456789Z\"}");
    }

    @Test
    void backupRunDtoKeepsNullsAndNestedArchive() throws Exception {
        assertWire("/wire/backup-run",
            "{\"runId\":\"run-1\",\"startTime\":\"2026-11-11T18:00:00Z\",\"endTime\":\"2026-11-11T18:05:00Z\","
                + "\"servicesRan\":[\"a\",\"b\"],\"archive\":{\"id\":\"id-1\",\"fileName\":\"backup-1.zip\","
                + "\"sizeBytes\":10,\"createdAt\":\"2026-11-11T18:00:00Z\"}}");
        assertWire("/wire/backup-run-empty",
            "{\"runId\":\"run-1\",\"startTime\":\"s\",\"endTime\":\"e\",\"servicesRan\":[],\"archive\":null}");
    }

    @Test
    void pairedClientDtoWritesNullLastUsedExplicitly() throws Exception {
        assertWire("/wire/paired-client",
            "{\"id\":\"c-1\",\"name\":\"Living room\",\"createdAt\":\"2026-11-11T18:00:00Z\",\"lastUsedAt\":null,\"current\":true}");
        assertWire("/wire/paired-client-used",
            "{\"id\":\"c-1\",\"name\":\"Living room\",\"createdAt\":\"2026-11-11T18:00:00Z\","
                + "\"lastUsedAt\":\"2026-11-11T18:00:00.123Z\",\"current\":false}");
        assertWire("/wire/paired-client-model",
            "{\"id\":\"c-1\",\"name\":\"Living room\",\"createdAt\":\"2026-11-11T18:00:00Z\",\"lastUsedAt\":\"2026-11-11T18:00:00.123Z\"}");
    }

    @Test
    void pairingDtosKeepTheirShape() throws Exception {
        assertWire("/wire/pairing-code", "{\"code\":\"ABCD-EFGH\",\"expiresAt\":\"2026-11-11T18:00:00Z\"}");
        assertWire("/wire/pair-response", "{\"token\":\"tok\",\"id\":\"c-1\",\"name\":\"Living room\"}");
    }

    @Test
    void durationsAreWrittenAsIso8601Strings() throws Exception {
        assertWire("/wire/public-health", "{\"healthy\":true,\"backupInterval\":\"PT12H\"}");
        assertWire("/wire/server-config",
            "{\"backupLocation\":\"/var/lib/mediagarrd/backups\",\"retentionCount\":10,\"backupInterval\":\"PT1H30M\"}");
        assertWire("/wire/server-status",
            "{\"healthy\":true,\"running\":false,\"backupRetentionCount\":10,\"backupInterval\":\"PT1H1M1S\"}");
    }

    @Test
    void statusDtosKeepTheirShape() throws Exception {
        assertWire("/wire/setup-status", "{\"completed\":false,\"authorized\":true}");
        assertWire("/wire/setup-complete", "{\"pairingPin\":\"123456\"}");
        assertWire("/wire/setup-complete-no-pin", "{\"pairingPin\":null}");
        assertWire("/wire/version-status",
            "{\"currentVersion\":\"0.1.0\",\"latestVersion\":\"0.2.0\",\"releaseUrl\":\"https://example.invalid/r\","
                + "\"updateAvailable\":true,\"checkState\":\"OK\",\"checkedAt\":\"2026-11-11T18:00:00.123Z\",\"message\":null}");
        assertWire("/wire/version-status-error",
            "{\"currentVersion\":\"0.1.0\",\"latestVersion\":null,\"releaseUrl\":null,\"updateAvailable\":false,"
                + "\"checkState\":\"NO_RELEASES\",\"checkedAt\":\"2026-11-11T18:00:00Z\",\"message\":\"none\"}");
    }

    @Test
    void serviceDefinitionKeepsNameAndPaths() throws Exception {
        assertWire("/wire/service-definition", "{\"name\":\"plex\",\"paths\":[\"/a\",\"/b\"]}");
        assertEcho("/wire/echo/service-definition", "{\"name\":\"plex\",\"paths\":[\"/a\",\"/b\"]}",
            "{\"name\":\"plex\",\"paths\":[\"/a\",\"/b\"]}");
    }

    @Test
    void requestDtosReadTheDocumentedBodies() throws Exception {
        assertEcho("/wire/echo/config-update", "{\"backupLocation\":\"/data\",\"retentionCount\":3}",
            "{\"backupLocation\":\"/data\",\"retentionCount\":3}");
        assertEcho("/wire/echo/config-update", "{}", "{\"backupLocation\":null,\"retentionCount\":null}");
        assertEcho("/wire/echo/pair-request", "{\"pin\":\"123456\",\"name\":\"TV\"}", "{\"pin\":\"123456\",\"name\":\"TV\"}");
        assertEcho("/wire/echo/pair-request", "{\"pin\":\"123456\"}", "{\"pin\":\"123456\",\"name\":null}");
        assertEcho("/wire/echo/setup-session", "{\"pin\":\"123456\"}", "{\"pin\":\"123456\"}");
    }

    @Test
    void durationRequestsAcceptIsoStrings() throws Exception {
        assertEcho("/wire/echo/schedule-update", "{\"backupInterval\":\"PT6H\"}", "{\"backupInterval\":\"PT6H\"}");
        assertEcho("/wire/echo/schedule-update", "{\"backupInterval\":\"PT90M\"}", "{\"backupInterval\":\"PT1H30M\"}");
    }

    @Test
    void unknownPropertiesInRequestsAreIgnored() throws Exception {
        assertEcho("/wire/echo/pair-request", "{\"pin\":\"1\",\"name\":\"TV\",\"extra\":true}", "{\"pin\":\"1\",\"name\":\"TV\"}");
        assertEcho("/wire/echo/config-update", "{\"retentionCount\":2,\"unknown\":[1,2]}",
            "{\"backupLocation\":null,\"retentionCount\":2}");
    }

    @Test
    void malformedBodiesAreRejectedAsBadRequests() throws Exception {
        mockMvc.perform(post("/wire/echo/pair-request").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/wire/echo/schedule-update").contentType(MediaType.APPLICATION_JSON)
                .content("{\"backupInterval\":\"not-a-duration\"}"))
            .andExpect(status().isBadRequest());
    }
}
