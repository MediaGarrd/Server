package wellatleastitried.mediagarrdServer.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import jakarta.servlet.http.Cookie;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;
import wellatleastitried.mediagarrdServer.service.DatabaseService;
import wellatleastitried.mediagarrdServer.service.SetupService;

@WebMvcTest(SetupController.class)
class SetupControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SetupService setup;

    @MockBean
    DatabaseService db;

    @Test
    void reportsStatusForTheCallersSession() throws Exception {
        when(setup.canConfigure("token")).thenReturn(true);

        mockMvc.perform(get("/api/v1/setup").cookie(new Cookie(SetupInterceptor.SESSION_COOKIE, "token")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.completed").value(false))
            .andExpect(jsonPath("$.authorized").value(true));

        mockMvc.perform(get("/api/v1/setup"))
            .andExpect(jsonPath("$.authorized").value(false));
    }

    @Test
    void correctPinSetsHttpOnlySessionCookie() throws Exception {
        when(setup.startSession("123456")).thenReturn(Optional.of("token"));

        mockMvc.perform(pin("123456"))
            .andExpect(status().isNoContent())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(SetupInterceptor.SESSION_COOKIE + "=token")))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")));
    }

    @Test
    void wrongPinIsUnauthorized() throws Exception {
        when(setup.startSession("000000")).thenReturn(Optional.empty());

        mockMvc.perform(pin("000000"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").exists())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void pinIsGoneAfterSetupCompletes() throws Exception {
        when(setup.isCompleted()).thenReturn(true);

        mockMvc.perform(pin("123456")).andExpect(status().isGone());

        verify(setup, never()).startSession(any());
    }

    @Test
    void completeRequiresSession() throws Exception {
        mockMvc.perform(post("/api/v1/setup/complete")).andExpect(status().isUnauthorized());

        verify(setup, never()).complete();
    }

    @Test
    void completeRequiresAtLeastOneService() throws Exception {
        when(setup.canConfigure(any())).thenReturn(true);
        when(db.fetchServices()).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/setup/complete"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Add at least one service before finishing setup"));

        verify(setup, never()).complete();
    }

    @Test
    void completeFinishesSetupAndClearsCookie() throws Exception {
        when(setup.canConfigure(any())).thenReturn(true);
        when(db.fetchServices()).thenReturn(List.of(new ServiceDefinition("radarr", List.of("/srv/radarr"))));

        mockMvc.perform(post("/api/v1/setup/complete"))
            .andExpect(status().isNoContent())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        verify(setup).complete();
    }

    @Test
    void completeIsGoneWhenAlreadyCompleted() throws Exception {
        when(setup.isCompleted()).thenReturn(true);
        when(setup.canConfigure(any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/setup/complete")).andExpect(status().isGone());

        verify(setup, never()).complete();
    }

    @Test
    void setupPageIsServedOnlyUntilSetupCompletes() throws Exception {
        mockMvc.perform(get("/config")).andExpect(status().isOk());

        when(setup.isCompleted()).thenReturn(true);

        mockMvc.perform(get("/config")).andExpect(status().isNotFound());
        mockMvc.perform(get("/config.html")).andExpect(status().isNotFound());
        mockMvc.perform(get("/js/config.js")).andExpect(status().isNotFound());
    }

    private static RequestBuilder pin(String pin) {
        return post("/api/v1/setup/session").contentType(MediaType.APPLICATION_JSON).content("{\"pin\": \"" + pin + "\"}");
    }
}
