package wellatleastitried.mediagarrdServer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import jakarta.servlet.http.Cookie;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;
import wellatleastitried.mediagarrdServer.repository.ServiceRepository;
import wellatleastitried.mediagarrdServer.service.PairingService;
import wellatleastitried.mediagarrdServer.service.SetupService;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiAuthSetupPhaseFullContextTest {

    private static final Path DATA_DIR = FullContextSupport.newDataDir();
    private static final List<String> SETUP_ENDPOINTS = List.of(
        "/api/v1/services", "/api/v1/config", "/api/v1/services;x", "/api/v1;x/services", "/api/v1/config;x");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        FullContextSupport.register(registry, DATA_DIR);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ServiceRepository services;

    @Autowired
    SetupService setup;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @Order(0)
    void everyResponseForbidsFraming() throws Exception {
        for (String path : List.of("/config", "/config.html", "/api/v1/health", "/api/v1/services", "/no-such-page.html")) {
            mockMvc.perform(get(path))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", SecurityHeadersFilter.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        }
    }

    @Test
    @Order(1)
    void setupEndpointsNeedASetupSessionAndEverythingElseNeedsAToken() throws Exception {
        for (String path : SETUP_ENDPOINTS) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/v1/backups")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/events")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/clients")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/config")).andExpect(status().isOk());

        Cookie session = startSession();

        for (String path : List.of("/api/v1/services", "/api/v1/config", "/api/v1/services;x")) {
            mockMvc.perform(get(path).cookie(session)).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/backups").cookie(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/events").cookie(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/clients").cookie(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/pairing-codes").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    void everyCallTheSetupPageMakesWorksWithTheSetupSession() throws Exception {
        Cookie session = startSession();
        String json = MediaType.APPLICATION_JSON_VALUE;

        mockMvc.perform(get("/api/v1/setup").cookie(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.authorized").value(true));
        mockMvc.perform(get("/api/v1/config").cookie(session)).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/config").cookie(session).contentType(json).content("{\"retentionCount\": 5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.retentionCount").value(5));
        mockMvc.perform(put("/api/v1/schedule").cookie(session).contentType(json).content("{\"backupInterval\": \"PT2H\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/services").cookie(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/services").cookie(session).contentType(json)
                .content("{\"name\": \"lidarr\", \"paths\": [\"/srv/lidarr\"]}"))
            .andExpect(status().isCreated());
        mockMvc.perform(put("/api/v1/services/lidarr").cookie(session).contentType(json)
                .content("{\"name\": \"lidarr\", \"paths\": [\"/srv/lidarr\", \"/srv/lidarr2\"]}"))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/services/lidarr").cookie(session)).andExpect(status().isNoContent());

        mockMvc.perform(put("/api/v1/schedule").contentType(json).content("{\"backupInterval\": \"PT2H\"}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/config").contentType(json).content("{\"retentionCount\": 5}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/services").contentType(json)
                .content("{\"name\": \"lidarr\", \"paths\": [\"/srv/lidarr\"]}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/services/radarr")).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(6)
    void theSetupSessionRoutesAreExactlyTheOnesTheSetupPageCalls() throws Exception {
        Set<String> pageCalls = new TreeSet<>();
        Matcher matcher = Pattern.compile("request\\('(\\w+)', (?:'([^']+)'|servicePath\\(name\\))")
            .matcher(Files.readString(Path.of("src/main/resources/static/js/api.js")));
        while (matcher.find()) {
            pageCalls.add(matcher.group(1) + " " + (matcher.group(2) != null ? matcher.group(2) : "/api/v1/services/{name}"));
        }
        assertTrue(pageCalls.size() >= 10, "Parsed only " + pageCalls);

        Set<String> setupRoutes = new TreeSet<>();
        Set<String> publicRoutes = new TreeSet<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            for (String pattern : entry.getKey().getPathPatternsCondition().getPatternValues()) {
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    String key = method.name() + " " + pattern;
                    if (entry.getValue().hasMethodAnnotation(SetupSessionAllowed.class)) {
                        setupRoutes.add(key);
                    }
                    if (entry.getValue().hasMethodAnnotation(PublicEndpoint.class)) {
                        publicRoutes.add(key);
                    }
                }
            }
        }

        Set<String> tokenOrSession = new TreeSet<>(pageCalls);
        tokenOrSession.removeAll(publicRoutes);
        assertEquals(tokenOrSession, setupRoutes);
    }

    @Test
    @Order(3)
    void pairingIsRefusedBeforeSetupCompletes() throws Exception {
        mockMvc.perform(post("/api/v1/pair").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\": \"" + FullContextSupport.currentPin(setup) + "\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("This server has not finished setup yet. Finish it on the server's /config page, then pair."));
    }

    @Test
    @Order(4)
    void completingSetupOpensPairingAndRetiresTheSetupPageAndSession() throws Exception {
        services.addService(new ServiceDefinition("radarr", List.of("/srv/radarr")));
        Cookie session = startSession();

        mockMvc.perform(post("/api/v1/setup/complete")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/setup/complete").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pairingPin").value(FullContextSupport.currentPin(setup)))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(cookie().maxAge(SetupInterceptor.SESSION_COOKIE, 0));

        assertTrue(setup.isCompleted());
        assertTrue(setup.isPairingOpen());
        mockMvc.perform(get("/api/v1/services").cookie(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/services;x").cookie(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/config")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/setup/session").contentType(MediaType.APPLICATION_JSON).content("{\"pin\": \"123456\"}"))
            .andExpect(status().isGone());
    }

    @Test
    @Order(5)
    void wrongPinsLockPairingOverHttp() throws Exception {
        String current = FullContextSupport.currentPin(setup);
        String wrong = current.substring(0, 5) + (current.charAt(5) == '0' ? '1' : '0');
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(pair(wrong)).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(pair(FullContextSupport.currentPin(setup)))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
            .andExpect(jsonPath("$.error").exists());
    }

    private Cookie startSession() throws Exception {
        String value = mockMvc.perform(post("/api/v1/setup/session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\": \"" + FullContextSupport.currentPin(setup) + "\"}"))
            .andExpect(status().isNoContent())
            .andReturn().getResponse().getCookie(SetupInterceptor.SESSION_COOKIE).getValue();
        return new Cookie(SetupInterceptor.SESSION_COOKIE, value);
    }

    private static org.springframework.test.web.servlet.RequestBuilder pair(String pin) {
        return post("/api/v1/pair").contentType(MediaType.APPLICATION_JSON).content("{\"pin\": \"" + pin + "\"}");
    }
}
