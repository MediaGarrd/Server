package wellatleastitried.mediagarrdServer.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import wellatleastitried.mediagarrdServer.model.ServiceDefinition;
import wellatleastitried.mediagarrdServer.service.BackupArchiveService;
import wellatleastitried.mediagarrdServer.service.BackupOrchestratorService;
import wellatleastitried.mediagarrdServer.service.DatabaseService;
import wellatleastitried.mediagarrdServer.service.DockerService;
import wellatleastitried.mediagarrdServer.service.SetupService;

@WebMvcTest(ConfigController.class)
class ConfigControllerTest {

    private static final ServiceDefinition RADARR = new ServiceDefinition("radarr", List.of("/srv/radarr"));
    private static final String RADARR_JSON = """
        {"name": "radarr", "paths": ["/srv/radarr"]}
        """;

    @Autowired
    MockMvc mockMvc;

    @MockBean
    BackupArchiveService archiveService;

    @MockBean
    DatabaseService db;

    @MockBean
    DockerService docker;

    @MockBean
    SetupService setup;

    @MockBean
    BackupOrchestratorService orchestrator;

    @BeforeEach
    void setUp() {
        when(setup.canConfigure(any())).thenReturn(true);
        when(orchestrator.whileIdle(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        when(orchestrator.getInterval()).thenReturn(Duration.ofHours(6));
        when(archiveService.retentionCount()).thenReturn(4);
    }

    @Test
    void rejectsConfigChangesWhileBackupIsRunning() throws Exception {
        doThrow(new BackupOrchestratorService.BackupInProgressException("busy")).when(orchestrator).whileIdle(any());

        mockMvc.perform(json(post("/api/v1/services"), RADARR_JSON))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("busy"));
        mockMvc.perform(delete("/api/v1/services/radarr")).andExpect(status().isConflict());
        mockMvc.perform(json(put("/api/v1/services/radarr"), RADARR_JSON)).andExpect(status().isConflict());
        mockMvc.perform(json(put("/api/v1/config"), "{\"retentionCount\": 3}")).andExpect(status().isConflict());

        verify(db, never()).addService(any());
        verify(db, never()).removeService(anyString());
        verify(archiveService, never()).updateSettings(any(), any());
    }

    @Test
    void requiresSetupSessionUntilSetupIsComplete() throws Exception {
        when(setup.canConfigure(any())).thenReturn(false);

        mockMvc.perform(get("/api/v1/services"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(json(post("/api/v1/services"), RADARR_JSON))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(json(put("/api/v1/config"), "{\"backupLocation\": \"/new\"}"))
            .andExpect(status().isUnauthorized());

        verify(db, never()).fetchServices();
        verify(db, never()).addService(any());
        verify(archiveService, never()).updateSettings(any(), any());
    }

    @Test
    void returnsBackupLocation() throws Exception {
        when(archiveService.rootDirectory()).thenReturn(Path.of("/backups"));

        mockMvc.perform(get("/api/v1/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.backupLocation").value("/backups"))
            .andExpect(jsonPath("$.retentionCount").value(4))
            .andExpect(jsonPath("$.backupInterval").exists());
    }

    @Test
    void updatesBackupLocationAndRetentionCount() throws Exception {
        when(archiveService.rootDirectory()).thenReturn(Path.of("/new"));

        mockMvc.perform(json(put("/api/v1/config"), "{\"backupLocation\": \"/new\", \"retentionCount\": 4}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.backupLocation").value("/new"))
            .andExpect(jsonPath("$.retentionCount").value(4));

        verify(archiveService).updateSettings("/new", 4);
    }

    @Test
    void passesOmittedFieldsAsNull() throws Exception {
        when(archiveService.rootDirectory()).thenReturn(Path.of("/backups"));

        mockMvc.perform(json(put("/api/v1/config"), "{\"retentionCount\": 4}"))
            .andExpect(status().isOk());

        verify(archiveService).updateSettings(isNull(), eq(4));
    }

    @Test
    void rejectsInvalidBackupLocation() throws Exception {
        doThrow(new IllegalArgumentException("must be absolute")).when(archiveService).updateSettings("relative", null);

        mockMvc.perform(json(put("/api/v1/config"), "{\"backupLocation\": \"relative\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("must be absolute"));
    }

    @Test
    void listsServices() throws Exception {
        when(db.fetchServices()).thenReturn(List.of(RADARR));

        mockMvc.perform(get("/api/v1/services"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("radarr"))
            .andExpect(jsonPath("$[0].paths[0]").value("/srv/radarr"));
    }

    @Test
    void addsNormalizedService() throws Exception {
        when(db.fetchService("radarr")).thenReturn(Optional.empty());

        mockMvc.perform(json(post("/api/v1/services"), "{\"name\": \" radarr \", \"paths\": [\"/srv/radarr/\", \"\"]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("radarr"))
            .andExpect(jsonPath("$.paths", hasSize(1)));

        verify(db).addService(RADARR);
    }

    @Test
    void rejectsDuplicateService() throws Exception {
        when(db.fetchService("radarr")).thenReturn(Optional.of(RADARR));

        mockMvc.perform(json(post("/api/v1/services"), RADARR_JSON))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").exists());

        verify(db, never()).addService(any());
    }

    @Test
    void rejectsInvalidService() throws Exception {
        mockMvc.perform(json(post("/api/v1/services"), "{\"name\": \"radarr\", \"paths\": [\"relative\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Path must be absolute: relative"));

        verify(db, never()).addService(any());
    }

    @Test
    void updatesServiceKeepingContainersWhenNameUnchanged() throws Exception {
        when(db.fetchService("radarr")).thenReturn(Optional.of(RADARR));

        mockMvc.perform(json(put("/api/v1/services/radarr"), "{\"name\": \"radarr\", \"paths\": [\"/srv/other\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths[0]").value("/srv/other"));

        verify(db).updateService("radarr", new ServiceDefinition("radarr", List.of("/srv/other")));
        verify(docker, never()).removeContainers(anyString());
    }

    @Test
    void renamingServiceRemovesOldContainers() throws Exception {
        when(db.fetchService("RADARR")).thenReturn(Optional.of(RADARR));
        when(db.fetchService("movies")).thenReturn(Optional.empty());

        mockMvc.perform(json(put("/api/v1/services/RADARR"), "{\"name\": \"movies\", \"paths\": [\"/srv/radarr\"]}"))
            .andExpect(status().isOk());

        verify(db).updateService("radarr", new ServiceDefinition("movies", List.of("/srv/radarr")));
        verify(docker).removeContainers("radarr");
    }

    @Test
    void renamingOntoExistingServiceConflicts() throws Exception {
        when(db.fetchService("radarr")).thenReturn(Optional.of(RADARR));
        when(db.fetchService("sonarr")).thenReturn(Optional.of(new ServiceDefinition("sonarr", List.of("/srv/sonarr"))));

        mockMvc.perform(json(put("/api/v1/services/radarr"), "{\"name\": \"sonarr\", \"paths\": [\"/srv/radarr\"]}"))
            .andExpect(status().isConflict());

        verify(db, never()).updateService(anyString(), any());
    }

    @Test
    void updatingMissingServiceReturnsNotFound() throws Exception {
        when(db.fetchService("radarr")).thenReturn(Optional.empty());

        mockMvc.perform(json(put("/api/v1/services/radarr"), RADARR_JSON))
            .andExpect(status().isNotFound());
    }

    @Test
    void removesServiceAndItsContainers() throws Exception {
        when(db.fetchService("Radarr")).thenReturn(Optional.of(RADARR));

        mockMvc.perform(delete("/api/v1/services/Radarr"))
            .andExpect(status().isNoContent());

        verify(db).removeService("radarr");
        verify(docker).removeContainers("radarr");
    }

    @Test
    void removingMissingServiceReturnsNotFound() throws Exception {
        when(db.fetchService("radarr")).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/services/radarr"))
            .andExpect(status().isNotFound());

        verify(docker, never()).removeContainers(anyString());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
