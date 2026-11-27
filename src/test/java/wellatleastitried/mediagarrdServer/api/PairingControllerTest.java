package wellatleastitried.mediagarrdServer.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.delete;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.get;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.post;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import wellatleastitried.mediagarrdServer.dto.PairResponse;
import wellatleastitried.mediagarrdServer.dto.PairingCodeDto;
import wellatleastitried.mediagarrdServer.model.PairedClient;
import wellatleastitried.mediagarrdServer.service.LockedOutException;
import wellatleastitried.mediagarrdServer.service.PairingException;
import wellatleastitried.mediagarrdServer.service.PairingService;
import wellatleastitried.mediagarrdServer.service.SetupService;

@WebMvcTest(PairingController.class)
class PairingControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    SetupService setup;

    @BeforeEach
    void authorize() {
        ApiTestAuth.accept(pairing);
    }

    @Test
    void pairReturnsTheTokenWithoutAnyAuthorization() throws Exception {
        when(pairing.pair("123456", "Laptop")).thenReturn(new PairResponse("the-token", "abc", "Laptop"));

        mockMvc.perform(pair("{\"pin\": \"123456\", \"name\": \"Laptop\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("the-token"))
            .andExpect(jsonPath("$.id").value("abc"))
            .andExpect(jsonPath("$.name").value("Laptop"));
    }

    @Test
    void pairMapsRejectionsToStatusesWithErrorBodies() throws Exception {
        when(pairing.pair("1", null)).thenThrow(new PairingException(PairingException.Reason.INVALID, "Invalid PIN or pairing code"));
        when(pairing.pair("2", null)).thenThrow(new PairingException(PairingException.Reason.CLOSED, "Pairing is closed"));
        when(pairing.pair("3", null)).thenThrow(new PairingException(PairingException.Reason.SETUP_INCOMPLETE, "This server has not finished setup yet. Finish it on the server's /config page, then pair."));
        when(pairing.pair("4", null)).thenThrow(new LockedOutException(Duration.ofSeconds(90)));
        when(pairing.pair("5", null)).thenThrow(new IllegalArgumentException("Name must be 1 to 64 characters"));

        mockMvc.perform(pair("{\"pin\": \"1\"}")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid PIN or pairing code"));
        mockMvc.perform(pair("{\"pin\": \"2\"}")).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Pairing is closed"));
        mockMvc.perform(pair("{\"pin\": \"3\"}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("This server has not finished setup yet. Finish it on the server's /config page, then pair."));
        mockMvc.perform(pair("{\"pin\": \"4\"}")).andExpect(status().isTooManyRequests())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "90"))
            .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(pair("{\"pin\": \"5\"}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void pairWithAMalformedBodyIsABadRequestInTheErrorShape() throws Exception {
        mockMvc.perform(pair("not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void creatingACodeNeedsAToken() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/pairing-codes")).andExpect(status().isUnauthorized());
        when(pairing.createCode()).thenReturn(new PairingCodeDto("ABCDE-FGHJK", Instant.parse("2026-11-19T10:10:00Z")));

        mockMvc.perform(post("/api/v1/pairing-codes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("ABCDE-FGHJK"))
            .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void listingMarksTheCallersOwnRecord() throws Exception {
        Instant created = Instant.parse("2026-11-19T10:00:00Z");
        when(pairing.list()).thenReturn(List.of(ApiTestAuth.CLIENT, new PairedClient("c2", "Phone", created, created)));

        mockMvc.perform(get("/api/v1/clients"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].id").value("c1"))
            .andExpect(jsonPath("$[0].current").value(true))
            .andExpect(jsonPath("$[1].current").value(false))
            .andExpect(jsonPath("$[0].token").doesNotExist())
            .andExpect(jsonPath("$[0].tokenHash").doesNotExist());

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/clients")).andExpect(status().isUnauthorized());
    }

    @Test
    void revokingARecordAnswers204AndAMissingOneAnswers404() throws Exception {
        when(pairing.revoke("c2")).thenReturn(true);
        when(pairing.revoke("nope")).thenReturn(false);

        mockMvc.perform(delete("/api/v1/clients/c2")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/clients/nope"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Client not found"));
    }

    @Test
    void revokingNeedsAToken() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/clients/c1")).andExpect(status().isUnauthorized());

        verify(pairing, never()).revoke(any());
    }

    private static org.springframework.test.web.servlet.RequestBuilder pair(String body) {
        return MockMvcRequestBuilders.post("/api/v1/pair").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
