package wellatleastitried.mediagarrdServer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import wellatleastitried.mediagarrdServer.service.RunStatusBroadcaster;
import wellatleastitried.mediagarrdServer.service.SetupService;

@Timeout(30)
@WebMvcTest(RunEventsController.class)
class RunEventsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    RunStatusBroadcaster broadcaster;

    @MockBean
    SetupService setup;

    @Test
    void streamsEventsWithoutASetupSession() throws Exception {
        when(setup.isCompleted()).thenReturn(false);
        when(setup.canConfigure(null)).thenReturn(false);
        when(broadcaster.subscribe()).thenReturn(new SseEmitter(60_000L));

        mockMvc.perform(get("/api/v1/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted())
            .andExpect(status().isOk());
    }

    @Test
    void streamsEventsAfterSetupToo() throws Exception {
        when(setup.isCompleted()).thenReturn(true);
        when(broadcaster.subscribe()).thenReturn(new SseEmitter(60_000L));

        mockMvc.perform(get("/api/v1/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted());
    }

    @Test
    void answers503WhenTooManySubscribers() throws Exception {
        when(broadcaster.subscribe()).thenThrow(new RunStatusBroadcaster.SubscriberLimitException("full"));

        mockMvc.perform(get("/api/v1/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void aTimedOutStreamEndsQuietlyWithoutAnErrorPage() throws Exception {
        when(broadcaster.subscribe()).thenThrow(new org.springframework.web.context.request.async.AsyncRequestTimeoutException());

        var result = mockMvc.perform(get("/api/v1/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isOk())
            .andReturn();

        assertEquals("", result.getResponse().getContentAsString());
    }
}
