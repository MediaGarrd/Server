package wellatleastitried.mediagarrdServer.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.service.BackupArchiveService;
import wellatleastitried.mediagarrdServer.service.BackupOrchestratorService;
import wellatleastitried.mediagarrdServer.service.SetupService;

@WebMvcTest(BackupController.class)
class BackupControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockBean
    BackupArchiveService archiveService;

    @MockBean
    BackupOrchestratorService orchestratorService;

    @MockBean
    SetupService setup;

    @Test
    void healthReturnsServerStatus() throws Exception {
        when(orchestratorService.isRunning()).thenReturn(false);
        when(orchestratorService.getInterval()).thenReturn(Duration.ofHours(12));
        when(archiveService.retentionCount()).thenReturn(7);

        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.healthy").value(true))
            .andExpect(jsonPath("$.backupRetentionCount").value(7));
    }

    @Test
    void clientEndpointsNeverRequireSetupSession() throws Exception {
        when(archiveService.listArchives()).thenReturn(List.of());
        when(orchestratorService.getInterval()).thenReturn(Duration.ofHours(12));

        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/backups")).andExpect(status().isOk());
    }

    @Test
    void runAndListBackups() throws Exception {
        String errorMessage = "testErrorMessage";
        BackupArchive archive = new BackupArchive(
            "backup-1",
            "backup-1.zip",
            Path.of("backup-1.zip"),
            12,
            Instant.parse("2026-01-01T00:00:00Z")
        );
        BackupRunResult result = new BackupRunResult(
            "run-1",
            "2026-01-01T00:00:00Z",
            "2026-01-01T00:01:00Z",
            "testStatus",
            List.of(),
            archive,
            errorMessage
        );
        when(orchestratorService.runBackup()).thenReturn(result);
        when(archiveService.listArchives()).thenReturn(List.of(archive));

        mockMvc.perform(post("/api/v1/backups/run"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.archive.id").isNotEmpty());

        mockMvc.perform(get("/api/v1/backups"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void rejectedRunReturnsConflictWithReason() throws Exception {
        when(orchestratorService.runBackup()).thenThrow(new BackupOrchestratorService.RunRejectedException("A backup run is already in progress"));

        mockMvc.perform(post("/api/v1/backups/run"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A backup run is already in progress"));
    }

    @Test
    void failedRunReturnsServerErrorWithReason() throws Exception {
        when(orchestratorService.runBackup()).thenThrow(new IllegalStateException("Every service failed, no archive created: radarr: boom"));

        mockMvc.perform(post("/api/v1/backups/run"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("Every service failed, no archive created: radarr: boom"));
    }
}
