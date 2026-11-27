package wellatleastitried.mediagarrdServer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.put;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.BACKUP_INTERVAL_SETTING;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.repository.TestDatabase;
import wellatleastitried.mediagarrdServer.service.BackupArchiveService;
import wellatleastitried.mediagarrdServer.service.BackupOrchestratorService;
import wellatleastitried.mediagarrdServer.service.BackupScheduleService;
import wellatleastitried.mediagarrdServer.service.PairingService;
import wellatleastitried.mediagarrdServer.service.RunStatusBroadcaster;
import wellatleastitried.mediagarrdServer.service.SetupService;

@WebMvcTest(BackupController.class)
class ScheduleIntervalApiTest {

    private static final String MESSAGE = "Backup interval must be between 15 minutes and 365 days";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    BackupArchiveService archiveService;

    @MockitoBean
    BackupOrchestratorService orchestratorService;

    @MockitoBean
    RunStatusBroadcaster broadcaster;

    @MockitoBean
    SetupService setup;

    @MockitoBean
    PairingService pairing;

    @TempDir
    Path tempDir;

    private TestDatabase db;
    private BackupScheduleService schedule;

    @BeforeEach
    void setUp() {
        ApiTestAuth.accept(pairing);
        MediaGarrdProperties properties = new MediaGarrdProperties();
        properties.setDatabasePath(tempDir.resolve("mediagarrd.db").toString());
        db = new TestDatabase(properties);
        schedule = new BackupScheduleService(properties, db.settings, db.backups, setup);
        when(orchestratorService.updateInterval(any())).thenAnswer(invocation -> schedule.updateInterval(invocation.getArgument(0)));
        when(orchestratorService.getInterval()).thenAnswer(invocation -> schedule.getInterval());
    }

    private org.springframework.test.web.servlet.ResultActions putSchedule(String body) throws Exception {
        return mockMvc.perform(put("/api/v1/schedule").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void rejectedIntervalsAnswer400AndSaveNothing() throws Exception {
        putSchedule("{\"backupInterval\": \"PT2H\"}").andExpect(status().isOk());
        Duration before = schedule.getInterval();

        List<String> bodies = List.of(
            "{}",
            "{\"backupInterval\": null}",
            "{\"backupInterval\": \"PT0S\"}",
            "{\"backupInterval\": \"-PT5M\"}",
            "{\"backupInterval\": \"PT14M59S\"}",
            "{\"backupInterval\": \"PT1S\"}",
            "{\"backupInterval\": \"P365DT1S\"}",
            "{\"backupInterval\": \"PT600000000000000M\"}",
            "{\"backupInterval\": 0}",
            "{\"backupInterval\": -60}",
            "{\"backupInterval\": 899}",
            "{\"backupInterval\": 31536001}",
            "{\"backupInterval\": 36000000000000}");
        for (String body : bodies) {
            putSchedule(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(MESSAGE));
        }

        assertEquals(before, schedule.getInterval());
        assertEquals(before.toString(), db.settings.fetchSetting(BACKUP_INTERVAL_SETTING).orElseThrow());
    }

    @Test
    void boundaryIntervalsAreAccepted() throws Exception {
        putSchedule("{\"backupInterval\": \"PT15M\"}").andExpect(status().isOk());
        assertEquals(Duration.ofMinutes(15), schedule.getInterval());
        putSchedule("{\"backupInterval\": \"P365D\"}").andExpect(status().isOk());
        assertEquals(Duration.ofDays(365), schedule.getInterval());
        putSchedule("{\"backupInterval\": 900}").andExpect(status().isOk());
        assertEquals(Duration.ofMinutes(15), schedule.getInterval());
        putSchedule("{\"backupInterval\": 31536000}").andExpect(status().isOk());
        assertEquals(Duration.ofDays(365), schedule.getInterval());
    }
}
