package wellatleastitried.mediagarrdServer.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.get;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.post;
import static wellatleastitried.mediagarrdServer.api.ApiTestAuth.put;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseUtils.FAILURE_WEBHOOK_SETTING;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;
import wellatleastitried.mediagarrdServer.repository.SettingsRepository;
import wellatleastitried.mediagarrdServer.service.PairingService;
import wellatleastitried.mediagarrdServer.service.RunStatusBroadcaster;
import wellatleastitried.mediagarrdServer.service.SetupService;
import wellatleastitried.mediagarrdServer.service.WebhookService;

@WebMvcTest(WebhookController.class)
@Import(WebhookService.class)
class WebhookControllerTest {

    private static final String SECRET_URL = "https://discord.com/api/webhooks/123456/SECRETTOKEN123";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SettingsRepository settings;

    @MockitoBean
    RunStatusBroadcaster broadcaster;

    @MockitoBean
    SetupService setup;

    @MockitoBean
    PairingService pairing;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void setUp() {
        ApiTestAuth.accept(pairing);
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
    }

    @Test
    void reportsNotConfiguredByDefault() throws Exception {
        when(settings.fetchSetting(FAILURE_WEBHOOK_SETTING)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/notifications/webhook"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"configured\":false,\"target\":null}", true));
    }

    @Test
    void theGetMasksTheUrlDownToSchemeAndHost() throws Exception {
        when(settings.fetchSetting(FAILURE_WEBHOOK_SETTING)).thenReturn(Optional.of(SECRET_URL));

        MvcResult result = mockMvc.perform(get("/api/v1/notifications/webhook"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"configured\":true,\"target\":\"https://discord.com\"}", true))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("SECRETTOKEN123") || body.contains("webhooks"));
    }

    @Test
    void thePutSavesTheUrlAndAnswersWithTheMaskedStatus() throws Exception {
        when(settings.fetchSetting(FAILURE_WEBHOOK_SETTING)).thenReturn(Optional.of(SECRET_URL));

        MvcResult result = mockMvc.perform(json(put("/api/v1/notifications/webhook"), "{\"url\": \"" + SECRET_URL + "\"}"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"configured\":true,\"target\":\"https://discord.com\"}", true))
            .andReturn();

        verify(settings).saveSetting(FAILURE_WEBHOOK_SETTING, SECRET_URL);
        verify(broadcaster).publishConfigChanged();
        assertFalse(result.getResponse().getContentAsString().contains("SECRETTOKEN123"));
        assertFalse(logText().contains("SECRETTOKEN123"));
    }

    @Test
    void thePutClearsWithAnEmptyStringOrNull() throws Exception {
        when(settings.fetchSetting(FAILURE_WEBHOOK_SETTING)).thenReturn(Optional.of(""));

        mockMvc.perform(json(put("/api/v1/notifications/webhook"), "{\"url\": \"\"}"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"configured\":false,\"target\":null}", true));
        mockMvc.perform(json(put("/api/v1/notifications/webhook"), "{\"url\": null}"))
            .andExpect(status().isOk());
        mockMvc.perform(json(put("/api/v1/notifications/webhook"), "{}"))
            .andExpect(status().isOk());

        verify(settings, org.mockito.Mockito.times(3)).saveSetting(FAILURE_WEBHOOK_SETTING, "");
    }

    @Test
    void aBadUrlIsA400WithAFixedMessageThatNeverEchoesTheInput() throws Exception {
        String body = "{\"url\": \"https://discord.com/api/webhooks/123/SECRETTOKEN123|\"}";

        MvcResult result = mockMvc.perform(json(put("/api/v1/notifications/webhook"), body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Webhook URL is not a valid URL"))
            .andReturn();

        verify(settings, never()).saveSetting(anyString(), anyString());
        verify(broadcaster, never()).publishConfigChanged();
        assertFalse(result.getResponse().getContentAsString().contains("SECRETTOKEN123"));
        assertFalse(logText().contains("SECRETTOKEN123"));
    }

    @Test
    void userInfoAndWrongSchemesAreRejected() throws Exception {
        mockMvc.perform(json(put("/api/v1/notifications/webhook"), "{\"url\": \"https://user:pw@example.com/x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Webhook URL must not contain user info"));
        mockMvc.perform(json(put("/api/v1/notifications/webhook"), "{\"url\": \"file:///etc/passwd\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Webhook URL must use http or https"));
        mockMvc.perform(json(put("/api/v1/notifications/webhook"), "{\"url\": 5}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void aTestWithoutAWebhookIsA400() throws Exception {
        when(settings.fetchSetting(FAILURE_WEBHOOK_SETTING)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/notifications/webhook/test"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("No webhook is configured"));
    }

    @Test
    void aTestAnswersWithTheDeliveryResultAndThenThe429Cooldown() throws Exception {
        when(settings.fetchSetting(FAILURE_WEBHOOK_SETTING)).thenReturn(Optional.of("http://127.0.0.1:1/hook"));

        mockMvc.perform(post("/api/v1/notifications/webhook/test"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"delivered\":false,\"status\":null,\"error\":\"Could not connect\"}", true));

        mockMvc.perform(post("/api/v1/notifications/webhook/test"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Wait ")));
    }

    @Test
    void theEndpointsNeedATokenOrASetupSession() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/notifications/webhook")).andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/notifications/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/notifications/webhook/test")).andExpect(status().isUnauthorized());

        verify(settings, never()).saveSetting(anyString(), anyString());
    }

    @Test
    void aSetupSessionIsEnoughBeforeSetupCompletesAndIgnoredAfter() throws Exception {
        when(setup.canConfigure("session")).thenReturn(true);
        when(settings.fetchSetting(eq(FAILURE_WEBHOOK_SETTING))).thenReturn(Optional.empty());

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/notifications/webhook").cookie(new Cookie(SetupInterceptor.SESSION_COOKIE, "session")))
            .andExpect(status().isOk());

        when(setup.isCompleted()).thenReturn(true);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/notifications/webhook").cookie(new Cookie(SetupInterceptor.SESSION_COOKIE, "session")))
            .andExpect(status().isUnauthorized());
    }

    private String logText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            text.append(event.getFormattedMessage()).append('\n');
        }
        return text.toString();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
