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
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.delete;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.get;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.post;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import jakarta.servlet.http.Cookie;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;
import wellatleastitried.mediagarrdServer.repository.ServiceRepository;
import wellatleastitried.mediagarrdServer.service.BackupArchiveService;
import wellatleastitried.mediagarrdServer.service.BackupInProgressException;
import wellatleastitried.mediagarrdServer.service.BackupOrchestratorService;
import wellatleastitried.mediagarrdServer.service.BackupRunLifecycle;
import wellatleastitried.mediagarrdServer.service.DockerService;
import wellatleastitried.mediagarrdServer.service.PairingService;
import wellatleastitried.mediagarrdServer.service.RunStatusBroadcaster;
import wellatleastitried.mediagarrdServer.service.ServiceConfigService;
import wellatleastitried.mediagarrdServer.service.SetupService;

@WebMvcTest(ConfigController.class)
@Import(ServiceConfigService.class)
class ConfigControllerTest {

    private static final ServiceDefinition RADARR = new ServiceDefinition("radarr", List.of("/srv/radarr"));
    private static final String RADARR_JSON = """
        {"name": "radarr", "paths": ["/srv/radarr"]}
        """;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    BackupArchiveService archiveService;

    @MockitoBean
    ServiceRepository services;

    @MockitoBean
    DockerService docker;

    @MockitoBean
    RunStatusBroadcaster broadcaster;

    @MockitoBean
    SetupService setup;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    BackupOrchestratorService orchestrator;

    @MockitoBean
    BackupRunLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        ApiTestAuth.accept(pairing);
        when(lifecycle.whileIdle(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        when(orchestrator.getInterval()).thenReturn(Duration.ofHours(6));
        when(archiveService.retentionCount()).thenReturn(4);
    }

    @Test
    void rejectsConfigChangesWhileBackupIsRunning() throws Exception {
        doThrow(new BackupInProgressException("busy")).when(lifecycle).whileIdle(any());

        mockMvc.perform(json(post("/api/v1/services"), RADARR_JSON))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("busy"));
        mockMvc.perform(delete("/api/v1/services/radarr")).andExpect(status().isConflict());
        mockMvc.perform(json(put("/api/v1/services/radarr"), RADARR_JSON)).andExpect(status().isConflict());
        mockMvc.perform(json(put("/api/v1/config"), "{\"retentionCount\": 3}")).andExpect(status().isConflict());

        verify(services, never()).addService(any());
        verify(services, never()).removeService(anyString());
        verify(archiveService, never()).updateSettings(any(), any());
        verify(broadcaster, never()).publishConfigChanged();
        verify(broadcaster, never()).publishBackupsChanged();
    }

    @Test
    void requiresSetupSessionUntilSetupIsComplete() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/services"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(json(MockMvcRequestBuilders.post("/api/v1/services"), RADARR_JSON))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(json(MockMvcRequestBuilders.put("/api/v1/config"), "{\"backupLocation\": \"/new\"}"))
            .andExpect(status().isUnauthorized());

        verify(services, never()).fetchServices();
        verify(services, never()).addService(any());
        verify(archiveService, never()).updateSettings(any(), any());
    }

    @Test
    void aSetupSessionIsEnoughBeforeSetupCompletes() throws Exception {
        when(setup.canConfigure("session")).thenReturn(true);
        when(services.fetchServices()).thenReturn(List.of(RADARR));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/services").cookie(new Cookie(SetupInterceptor.SESSION_COOKIE, "session")))
            .andExpect(status().isOk());
    }

    @Test
    void aSetupSessionIsIgnoredAfterSetupCompletes() throws Exception {
        when(setup.isCompleted()).thenReturn(true);
        when(setup.canConfigure("session")).thenReturn(true);

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/services").cookie(new Cookie(SetupInterceptor.SESSION_COOKIE, "session")))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    void aWrongTokenIsRejectedEvenBeforeSetupCompletes() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/services").header(HttpHeaders.AUTHORIZATION, "Bearer wrong"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid or revoked token, pair this client again"));
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
        verify(broadcaster).publishBackupsChanged();
        verify(broadcaster).publishConfigChanged();
    }

    @Test
    void passesOmittedFieldsAsNull() throws Exception {
        when(archiveService.rootDirectory()).thenReturn(Path.of("/backups"));

        mockMvc.perform(json(put("/api/v1/config"), "{\"retentionCount\": 4}"))
            .andExpect(status().isOk());

        verify(archiveService).updateSettings(isNull(), eq(4));
        verify(broadcaster, never()).publishBackupsChanged();
        verify(broadcaster).publishConfigChanged();
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
        when(services.fetchServices()).thenReturn(List.of(RADARR));

        mockMvc.perform(get("/api/v1/services"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("radarr"))
            .andExpect(jsonPath("$[0].paths[0]").value("/srv/radarr"));
    }

    @Test
    void addsNormalizedService() throws Exception {
        when(services.fetchService("radarr")).thenReturn(Optional.empty());

        mockMvc.perform(json(post("/api/v1/services"), "{\"name\": \" radarr \", \"paths\": [\"/srv/radarr/\", \"\"]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("radarr"))
            .andExpect(jsonPath("$.paths", hasSize(1)));

        verify(services).addService(RADARR);
        verify(orchestrator).servicesChanged();
        verify(broadcaster).publishConfigChanged();
    }

    @Test
    void rejectsDuplicateService() throws Exception {
        when(services.fetchService("radarr")).thenReturn(Optional.of(RADARR));

        mockMvc.perform(json(post("/api/v1/services"), RADARR_JSON))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").exists());

        verify(services, never()).addService(any());
        verify(orchestrator, never()).servicesChanged();
        verify(broadcaster, never()).publishConfigChanged();
    }

    @Test
    void rejectsInvalidService() throws Exception {
        mockMvc.perform(json(post("/api/v1/services"), "{\"name\": \"radarr\", \"paths\": [\"relative\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Path must be absolute: relative"));

        verify(services, never()).addService(any());
    }

    @Test
    void updatesServiceKeepingContainersWhenNameUnchanged() throws Exception {
        when(services.fetchService("radarr")).thenReturn(Optional.of(RADARR));

        mockMvc.perform(json(put("/api/v1/services/radarr"), "{\"name\": \"radarr\", \"paths\": [\"/srv/other\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths[0]").value("/srv/other"));

        verify(services).updateService("radarr", new ServiceDefinition("radarr", List.of("/srv/other")));
        verify(orchestrator).servicesChanged();
        verify(docker, never()).removeContainers(anyString());
        verify(broadcaster).publishConfigChanged();
    }

    @Test
    void renamingServiceRemovesOldContainers() throws Exception {
        when(services.fetchService("RADARR")).thenReturn(Optional.of(RADARR));
        when(services.fetchService("movies")).thenReturn(Optional.empty());

        mockMvc.perform(json(put("/api/v1/services/RADARR"), "{\"name\": \"movies\", \"paths\": [\"/srv/radarr\"]}"))
            .andExpect(status().isOk());

        verify(services).updateService("radarr", new ServiceDefinition("movies", List.of("/srv/radarr")));
        verify(docker).removeContainers("radarr");
    }

    @Test
    void renamingOntoExistingServiceConflicts() throws Exception {
        when(services.fetchService("radarr")).thenReturn(Optional.of(RADARR));
        when(services.fetchService("sonarr")).thenReturn(Optional.of(new ServiceDefinition("sonarr", List.of("/srv/sonarr"))));

        mockMvc.perform(json(put("/api/v1/services/radarr"), "{\"name\": \"sonarr\", \"paths\": [\"/srv/radarr\"]}"))
            .andExpect(status().isConflict());

        verify(services, never()).updateService(anyString(), any());
        verify(orchestrator, never()).servicesChanged();
        verify(broadcaster, never()).publishConfigChanged();
    }

    @Test
    void updatingMissingServiceReturnsNotFound() throws Exception {
        when(services.fetchService("radarr")).thenReturn(Optional.empty());

        mockMvc.perform(json(put("/api/v1/services/radarr"), RADARR_JSON))
            .andExpect(status().isNotFound());

        verify(orchestrator, never()).servicesChanged();
    }

    @Test
    void removesServiceAndItsContainers() throws Exception {
        when(services.fetchService("Radarr")).thenReturn(Optional.of(RADARR));

        mockMvc.perform(delete("/api/v1/services/Radarr"))
            .andExpect(status().isNoContent());

        verify(services).removeService("radarr");
        verify(docker).removeContainers("radarr");
        verify(broadcaster).publishConfigChanged();
    }

    @Test
    void removingMissingServiceReturnsNotFound() throws Exception {
        when(services.fetchService("radarr")).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/services/radarr"))
            .andExpect(status().isNotFound());

        verify(docker, never()).removeContainers(anyString());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
