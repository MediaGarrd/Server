package wellatleastitried.mediagarrdServer.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.get;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import jakarta.servlet.http.Cookie;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;
import wellatleastitried.mediagarrdServer.repository.ServiceRepository;
import wellatleastitried.mediagarrdServer.service.LockedOutException;
import wellatleastitried.mediagarrdServer.service.PairingService;
import wellatleastitried.mediagarrdServer.service.SetupAlreadyCompleteException;
import wellatleastitried.mediagarrdServer.service.SetupService;

@WebMvcTest(SetupController.class)
class SetupControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SetupService setup;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    ServiceRepository services;

    @BeforeEach
    void authorize() {
        ApiTestAuth.accept(pairing);
    }

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
    void lockedPinAnswers429WithRetryAfter() throws Exception {
        when(setup.startSession("000000")).thenThrow(new LockedOutException(Duration.ofMinutes(7)));

        mockMvc.perform(pin("000000"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "420"))
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void completeRequiresSession() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/setup/complete")).andExpect(status().isUnauthorized());

        verify(setup, never()).complete();
    }

    @Test
    void completeRequiresAtLeastOneService() throws Exception {
        when(setup.canConfigure(any())).thenReturn(true);
        when(services.fetchServices()).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/setup/complete"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Add at least one service before finishing setup"));

        verify(setup, never()).complete();
    }

    @Test
    void completeFinishesSetupAndClearsCookie() throws Exception {
        when(setup.canConfigure(any())).thenReturn(true);
        when(services.fetchServices()).thenReturn(List.of(new ServiceDefinition("radarr", List.of("/srv/radarr"))));
        when(setup.complete()).thenReturn("123456");

        mockMvc.perform(post("/api/v1/setup/complete"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pairingPin").value("123456"))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(setup).complete();
    }

    @Test
    void completeAnswersAnExplicitNullPinWhenNoPinApplies() throws Exception {
        when(setup.canConfigure(any())).thenReturn(true);
        when(services.fetchServices()).thenReturn(List.of(new ServiceDefinition("radarr", List.of("/srv/radarr"))));
        when(setup.complete()).thenReturn(null);

        mockMvc.perform(post("/api/v1/setup/complete"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"pairingPin\":null}", true));
    }

    @Test
    void completeIsGoneWhenARacingRequestCompletedFirst() throws Exception {
        when(setup.canConfigure(any())).thenReturn(true);
        when(services.fetchServices()).thenReturn(List.of(new ServiceDefinition("radarr", List.of("/srv/radarr"))));
        when(setup.complete()).thenThrow(new SetupAlreadyCompleteException());

        mockMvc.perform(post("/api/v1/setup/complete")).andExpect(status().isGone());
    }

    @Test
    void completeIsAGenericServerErrorWhenSavingFails() throws Exception {
        when(setup.canConfigure(any())).thenReturn(true);
        when(services.fetchServices()).thenReturn(List.of(new ServiceDefinition("radarr", List.of("/srv/radarr"))));
        when(setup.complete()).thenThrow(new IllegalStateException("Failed to save setting"));

        mockMvc.perform(post("/api/v1/setup/complete"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("Internal error"));
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

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pin(String pin) {
        return post("/api/v1/setup/session").contentType(MediaType.APPLICATION_JSON).content("{\"pin\": \"" + pin + "\"}");
    }

    @Test
    void theSessionCookieIsSecureOnlyOverHttps() throws Exception {
        when(setup.startSession("123456")).thenReturn(Optional.of("token"));

        mockMvc.perform(pin("123456"))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.not(containsString("Secure"))));
        mockMvc.perform(pin("123456").header("X-Forwarded-Proto", "https"))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")));
        mockMvc.perform(pin("123456").secure(true))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")));
    }
}
