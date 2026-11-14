package wellatleastitried.mediagarrdServer.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import wellatleastitried.mediagarrdServer.dto.CheckState;
import wellatleastitried.mediagarrdServer.dto.VersionStatusDto;
import wellatleastitried.mediagarrdServer.service.SetupService;
import wellatleastitried.mediagarrdServer.service.UpdateCheckService;

@WebMvcTest(VersionController.class)
class VersionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    UpdateCheckService updateCheckService;

    @MockBean
    SetupService setup;

    @Test
    void reportsTheVersionStatusWithoutASetupSession() throws Exception {
        when(setup.isCompleted()).thenReturn(false);
        when(setup.canConfigure(null)).thenReturn(false);
        when(updateCheckService.status()).thenReturn(new VersionStatusDto(
            "0.1.0", "0.2.0", "https://github.com/MediaGarrd/Server/releases/tag/v0.2.0", true,
            CheckState.OK, Instant.parse("2026-11-11T18:00:00Z"), null));

        mockMvc.perform(get("/api/v1/version"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentVersion").value("0.1.0"))
            .andExpect(jsonPath("$.latestVersion").value("0.2.0"))
            .andExpect(jsonPath("$.releaseUrl").value("https://github.com/MediaGarrd/Server/releases/tag/v0.2.0"))
            .andExpect(jsonPath("$.updateAvailable").value(true))
            .andExpect(jsonPath("$.checkState").value("OK"))
            .andExpect(jsonPath("$.checkedAt").value("2026-11-11T18:00:00Z"))
            .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void reportsAFailedCheckAsAState() throws Exception {
        when(updateCheckService.status()).thenReturn(new VersionStatusDto(
            "0.1.0", null, null, false, CheckState.ERROR, Instant.parse("2026-11-11T18:00:00Z"), "GitHub is unreachable"));

        mockMvc.perform(get("/api/v1/version"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checkState").value("ERROR"))
            .andExpect(jsonPath("$.updateAvailable").value(false))
            .andExpect(jsonPath("$.message").value("GitHub is unreachable"));
    }
}
