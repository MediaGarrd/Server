package wellatleastitried.mediagarrdServer.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.delete;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.get;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.post;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import wellatleastitried.mediagarrdServer.model.BackupArchive;
import wellatleastitried.mediagarrdServer.model.BackupRunResult;
import wellatleastitried.mediagarrdServer.service.BackupArchiveService;
import wellatleastitried.mediagarrdServer.service.BackupFailedException;
import wellatleastitried.mediagarrdServer.service.BackupOrchestratorService;
import wellatleastitried.mediagarrdServer.service.RunRejectedException;
import wellatleastitried.mediagarrdServer.service.RunStatusBroadcaster;
import wellatleastitried.mediagarrdServer.service.PairingService;
import wellatleastitried.mediagarrdServer.service.SetupService;

@WebMvcTest(BackupController.class)
class BackupControllerTest {
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

    @BeforeEach
    void authorize() {
        ApiTestAuth.accept(pairing);
    }

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
    void healthWithoutATokenIsMinimal() throws Exception {
        when(orchestratorService.getInterval()).thenReturn(Duration.ofHours(12));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.healthy").value(true))
            .andExpect(jsonPath("$.backupInterval").exists())
            .andExpect(jsonPath("$.running").doesNotExist())
            .andExpect(jsonPath("$.backupRetentionCount").doesNotExist());
    }

    @Test
    void healthWithAnInvalidTokenIsUnauthorized() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/health").header(HttpHeaders.AUTHORIZATION, "Bearer revoked"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void everythingElseNeedsAToken() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/backups")).andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/backups/latest/download")).andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/backups/run")).andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/backups/backup-1")).andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/schedule")
                .contentType(MediaType.APPLICATION_JSON).content("{\"backupInterval\": \"PT2H\"}"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(orchestratorService, archiveService);
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
            .andExpect(jsonPath("$.runId").value("run-1"))
            .andExpect(jsonPath("$.startTime").value("2026-01-01T00:00:00Z"))
            .andExpect(jsonPath("$.endTime").value("2026-01-01T00:01:00Z"))
            .andExpect(jsonPath("$.servicesRan", hasSize(0)))
            .andExpect(jsonPath("$.archive.id").value("backup-1"))
            .andExpect(jsonPath("$.archive.fileName").value("backup-1.zip"))
            .andExpect(jsonPath("$.archive.sizeBytes").value(12))
            .andExpect(jsonPath("$.archive.createdAt").value("2026-01-01T00:00:00Z"))
            .andExpect(jsonPath("$.archive.path").doesNotExist());

        mockMvc.perform(get("/api/v1/backups"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value("backup-1"))
            .andExpect(jsonPath("$[0].fileName").value("backup-1.zip"))
            .andExpect(jsonPath("$[0].sizeBytes").value(12))
            .andExpect(jsonPath("$[0].createdAt").value("2026-01-01T00:00:00Z"))
            .andExpect(jsonPath("$[0].path").doesNotExist());
    }

    @Test
    void latestAndByIdReturnTheBackupWithoutItsPath() throws Exception {
        BackupArchive archive = new BackupArchive("backup-2", "backup-2.zip", Path.of("/srv/backups/backup-2.zip"), 34, Instant.parse("2026-02-01T00:00:00Z"));
        when(archiveService.latest()).thenReturn(java.util.Optional.of(archive));
        when(archiveService.byId("backup-2")).thenReturn(java.util.Optional.of(archive));

        for (String url : List.of("/api/v1/backups/latest", "/api/v1/backups/backup-2")) {
            mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("backup-2"))
                .andExpect(jsonPath("$.fileName").value("backup-2.zip"))
                .andExpect(jsonPath("$.sizeBytes").value(34))
                .andExpect(jsonPath("$.createdAt").value("2026-02-01T00:00:00Z"))
                .andExpect(jsonPath("$.path").doesNotExist());
        }
    }

    @Test
    void theLatestDownloadStreamsTheNewestArchiveAsAnAttachment(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("latest.zip");
        java.nio.file.Files.writeString(file, "newest-bytes");
        BackupArchive archive = new BackupArchive("20261121-media-backup", "20261121-media-backup.zip", file, 12, Instant.EPOCH);
        when(archiveService.latest()).thenReturn(java.util.Optional.of(archive));

        mockMvc.perform(get("/api/v1/backups/latest/download"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"20261121-media-backup.zip\""))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(HttpHeaders.CONTENT_LENGTH, "12"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("newest-bytes"));
    }

    @Test
    void rejectedRunReturnsConflictWithReason() throws Exception {
        when(orchestratorService.runBackup()).thenThrow(new RunRejectedException("A backup run is already in progress"));

        mockMvc.perform(post("/api/v1/backups/run"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A backup run is already in progress"));
    }

    @Test
    void failedRunReturnsServerErrorWithReason() throws Exception {
        when(orchestratorService.runBackup()).thenThrow(new BackupFailedException("Every service failed, no archive created: radarr: boom"));

        mockMvc.perform(post("/api/v1/backups/run"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("Every service failed, no archive created: radarr: boom"));
    }

    @Test
    void unexpectedFailureHidesItsDetails() throws Exception {
        when(orchestratorService.runBackup()).thenThrow(new IllegalStateException("Failed to create run directory /data/server/backups/run-1"));

        mockMvc.perform(post("/api/v1/backups/run"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("Internal error"));
    }

    @Test
    void deletingABackupPublishesBackupsChanged() throws Exception {
        when(archiveService.deleteById("backup-1")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/backups/backup-1"))
            .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(broadcaster).publishBackupsChanged();
    }

    @Test
    void deletingAMissingBackupPublishesNothing() throws Exception {
        when(archiveService.deleteById("nope")).thenReturn(false);

        mockMvc.perform(delete("/api/v1/backups/nope"))
            .andExpect(status().isNotFound());

        org.mockito.Mockito.verify(broadcaster, org.mockito.Mockito.never()).publishBackupsChanged();
    }

    @Test
    void updatingTheSchedulePublishesConfigChanged() throws Exception {
        when(orchestratorService.updateInterval(Duration.ofHours(2))).thenReturn(Duration.ofHours(2));

        mockMvc.perform(put("/api/v1/schedule")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"backupInterval\": \"PT2H\"}"))
            .andExpect(status().isOk());

        org.mockito.Mockito.verify(broadcaster).publishConfigChanged();
    }

    @Test
    void notFoundAnswersWithTheErrorShape() throws Exception {
        when(archiveService.byId("nope")).thenReturn(java.util.Optional.empty());
        when(archiveService.latest()).thenReturn(java.util.Optional.empty());
        when(archiveService.deleteById("nope")).thenReturn(false);

        mockMvc.perform(get("/api/v1/backups/nope"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Backup not found"));
        mockMvc.perform(get("/api/v1/backups/latest"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No backup archives found"));
        mockMvc.perform(get("/api/v1/backups/latest/download"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No backup archives found"));
        mockMvc.perform(get("/api/v1/backups/nope/download"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Backup not found"));
        mockMvc.perform(delete("/api/v1/backups/nope"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Backup not found"));
    }

    @Test
    void aMissingArchiveFileIsNotFoundWithTheErrorShape() throws Exception {
        BackupArchive archive = new BackupArchive("gone", "gone.zip", Path.of("/nonexistent/gone.zip"), 1, Instant.EPOCH);
        when(archiveService.byId("gone")).thenReturn(java.util.Optional.of(archive));

        mockMvc.perform(get("/api/v1/backups/gone/download"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Backup file missing on disk"));
    }

    @Test
    void downloadsCarryAnAttachmentHeaderAndAreNotBrokenByNoStore() throws Exception {
        Path file = java.nio.file.Files.createTempFile("download", ".zip");
        java.nio.file.Files.writeString(file, "zip-bytes");
        BackupArchive archive = new BackupArchive("20261120-media-backup", "20261120-media-backup.zip", file, 9, Instant.EPOCH);
        when(archiveService.byId("20261120-media-backup")).thenReturn(java.util.Optional.of(archive));

        mockMvc.perform(get("/api/v1/backups/20261120-media-backup/download"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"20261120-media-backup.zip\""))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("zip-bytes"));
    }

    @Test
    void anUnauthenticatedCallBeforeSetupSaysSetupIsUnfinished() throws Exception {
        when(setup.isCompleted()).thenReturn(false);

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/backups"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Setup is not finished")));
    }

    @Test
    void anUnauthenticatedCallAfterSetupAsksForAToken() throws Exception {
        when(setup.isCompleted()).thenReturn(true);

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/backups"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Authorization required")));
    }

    @Test
    void springRejectionsUseTheErrorShapeToo() throws Exception {
        mockMvc.perform(post("/api/v1/backups/nope"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Allow"))
            .andExpect(jsonPath("$.error").value("Method Not Allowed"));
    }

    @Test
    void anOverlongBodyIsRefusedBeforeItReachesTheController() throws Exception {
        mockMvc.perform(put("/api/v1/schedule")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"backupInterval\": \"" + "P".repeat(RequestBodyLimitFilter.MAX_BODY_BYTES) + "\"}"))
            .andExpect(status().isContentTooLarge())
            .andExpect(jsonPath("$.error").value(RequestBodyLimitFilter.MESSAGE));
    }
}
