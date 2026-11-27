package wellatleastitried.mediagarrdServer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import wellatleastitried.mediagarrdServer.service.RunStatusBroadcaster;
import jakarta.servlet.http.Cookie;
import wellatleastitried.mediagarrdServer.service.PairingService;
import wellatleastitried.mediagarrdServer.service.SetupService;

@Timeout(30)
@WebMvcTest(RunEventsController.class)
class RunEventsControllerTest {

    @Autowired
    MockMvc mockMvc;

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
    void streamsEventsWithATokenWhetherOrNotSetupIsComplete() throws Exception {
        when(broadcaster.subscribe("c1")).thenReturn(new SseEmitter(60_000L));

        for (boolean completed : new boolean[] {false, true}) {
            when(setup.isCompleted()).thenReturn(completed);

            mockMvc.perform(get("/api/v1/events").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());
        }
    }

    @Test
    void refusesEventsWithoutATokenOrWithASetupSession() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isUnauthorized());

        when(setup.canConfigure("session")).thenReturn(true);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/events").accept(MediaType.TEXT_EVENT_STREAM)
                .cookie(new Cookie(SetupInterceptor.SESSION_COOKIE, "session")))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(broadcaster);
    }

    @Test
    void aClientRevokedWhileSubscribingIsClosedAndRefused() throws Exception {
        when(broadcaster.subscribe("c1")).thenReturn(new SseEmitter(60_000L));
        when(pairing.isPaired("c1")).thenReturn(false);

        mockMvc.perform(get("/api/v1/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isUnauthorized());

        verify(broadcaster).closeClient("c1");
    }

    @Test
    void answers503WhenTooManySubscribers() throws Exception {
        when(broadcaster.subscribe("c1")).thenThrow(new RunStatusBroadcaster.SubscriberLimitException("full"));

        mockMvc.perform(get("/api/v1/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void aTimedOutStreamEndsQuietlyWithoutAnErrorPage() throws Exception {
        when(broadcaster.subscribe("c1")).thenThrow(new org.springframework.web.context.request.async.AsyncRequestTimeoutException());

        var result = mockMvc.perform(get("/api/v1/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isOk())
            .andReturn();

        assertEquals("", result.getResponse().getContentAsString());
    }
}
