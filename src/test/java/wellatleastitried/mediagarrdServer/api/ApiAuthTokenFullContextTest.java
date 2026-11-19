package wellatleastitried.mediagarrdServer.api;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import tools.jackson.databind.json.JsonMapper;

import wellatleastitried.mediagarrdServer.repository.PairedClientRepository;
import wellatleastitried.mediagarrdServer.service.PairingService;
import wellatleastitried.mediagarrdServer.service.SetupService;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiAuthTokenFullContextTest {

    private static final Path DATA_DIR = FullContextSupport.newDataDir();
    private static final String TOKEN = "full-context-token-0123456789-abcdefghijklmnop";
    private static final Set<String> OPEN_ENDPOINTS = Set.of(
        "GET /api/v1/health", "GET /api/v1/version", "GET /api/v1/setup", "POST /api/v1/setup/session", "POST /api/v1/pair");
    private static final List<String> PROTECTED_VARIANTS = List.of(
        "/api/v1/services;x",
        "/api/v1/services;jsessionid=1",
        "/api/v1;x/services",
        "/api/v1/%73ervices",
        "/api/v1/services/",
        "/api/v1/services/%2e",
        "/api/v1/%2e/services",
        "//api/v1/services",
        "///api/v1/services",
        "/api/v1//services",
        "/API/v1/services",
        "/api/v1/backups;x/latest/download",
        "/api/v1/backups/latest/download/",
        "/api/v1/events;x",
        "/api/v1/clients;x");

    @BeforeAll
    static void seedCompletedSetup() {
        FullContextSupport.openDatabase(DATA_DIR).settings.saveSetting("setup_completed", "2026-11-19 10:00:00");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        FullContextSupport.register(registry, DATA_DIR);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PairedClientRepository pairedClients;

    @Autowired
    SetupService setup;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @AfterEach
    void forgetTestClient() {
        pairedClients.removePairedClient("test");
    }

    @Test
    @Order(1)
    void pairingWithThePinIssuesAWorkingTokenOnce() throws Exception {
        assertTrue(setup.isCompleted());
        assertTrue(setup.isPairingOpen());
        String pin = FullContextSupport.currentPin(setup);

        MvcResult paired = mockMvc.perform(post("/api/v1/pair").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\": \"" + pin + "\", \"name\": \"Test laptop\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andReturn();
        Map<?, ?> body = JsonMapper.builder().build().readValue(paired.getResponse().getContentAsString(), Map.class);
        String token = (String) body.get("token");
        String id = (String) body.get("id");

        MvcResult again = mockMvc.perform(post("/api/v1/pair").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\": \"" + pin + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        String unusedToken = (String) JsonMapper.builder().build().readValue(again.getResponse().getContentAsString(), Map.class).get("token");
        assertTrue(pairedClients.fetchPairedClients().isEmpty());

        mockMvc.perform(get("/api/v1/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/health").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.backupRetentionCount").exists());
        mockMvc.perform(get("/api/v1/clients").header(HttpHeaders.AUTHORIZATION, "bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].current").value(true));
        assertTrue(pairedClients.fetchPairedClientByTokenHash(PairingService.hashToken(token)).isPresent());
        mockMvc.perform(get("/api/v1/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + unusedToken))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/pair").contentType(MediaType.APPLICATION_JSON).content("{\"pin\": \"" + pin + "\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").exists());

        mockMvc.perform(delete("/api/v1/clients/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    void aCodeFromAPairedClientPairsAnotherOne() throws Exception {
        String token = addClient();

        MvcResult created = mockMvc.perform(post("/api/v1/pairing-codes").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").isNotEmpty())
            .andReturn();
        String code = (String) JsonMapper.builder().build().readValue(created.getResponse().getContentAsString(), Map.class).get("code");

        MvcResult paired = mockMvc.perform(post("/api/v1/pair").contentType(MediaType.APPLICATION_JSON).content("{\"pin\": \"" + code + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andReturn();
        String pending = (String) JsonMapper.builder().build().readValue(paired.getResponse().getContentAsString(), Map.class).get("token");
        mockMvc.perform(post("/api/v1/pair").contentType(MediaType.APPLICATION_JSON).content("{\"pin\": \"" + code + "\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + pending))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/pair").contentType(MediaType.APPLICATION_JSON).content("{\"pin\": \"" + code + "\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void missingWrongAndRevokedTokensAreRejected() throws Exception {
        String token = addClient();

        mockMvc.perform(get("/api/v1/services")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(get("/api/v1/services").header(HttpHeaders.AUTHORIZATION, "Bearer nope"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/services").header(HttpHeaders.AUTHORIZATION, "Bearer"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/services").header(HttpHeaders.AUTHORIZATION, "Basic " + token))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/services").header(HttpHeaders.AUTHORIZATION, token))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/health").header(HttpHeaders.AUTHORIZATION, "Bearer nope"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk());

        pairedClients.removePairedClient("test");

        mockMvc.perform(get("/api/v1/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    void healthWithoutATokenRevealsOnlyWhatDiscoveryNeeds() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.healthy").value(true))
            .andExpect(jsonPath("$.backupInterval").exists())
            .andExpect(jsonPath("$.running").doesNotExist())
            .andExpect(jsonPath("$.backupRetentionCount").doesNotExist());
    }

    @Test
    @Order(5)
    void pathVariantsNeverReachAProtectedHandlerWithoutAToken() throws Exception {
        for (String variant : PROTECTED_VARIANTS) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
                int status = mockMvc.perform(MockMvcRequestBuilders.request(method, variant)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus();
                assertTrue(status == 401 || status == 404 || status == 405,
                    method + " " + variant + " answered " + status + " without a token");
            }
        }
        for (String variant : List.of("/api/v1/services;x", "/api/v1;x/services", "/api/v1/%73ervices", "/api/v1/services/")) {
            mockMvc.perform(get(variant)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @Order(6)
    void aMatrixParameterVariantReachesTheHandlerOnlyWithAValidToken() throws Exception {
        String token = addClient();

        mockMvc.perform(get("/api/v1/services;x").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    @Order(7)
    void everyRegisteredApiRouteOutsideTheAllowlistRequiresAToken() throws Exception {
        Set<String> reachableWithoutToken = new TreeSet<>();
        Set<String> annotatedPublic = new TreeSet<>();
        int routes = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<String> patterns = info.getPathPatternsCondition().getPatternValues();
            for (String pattern : patterns) {
                if (pattern.equals("/error")) {
                    continue;
                }
                assertTrue(pattern.startsWith("/api/v1"), "Route outside /api/v1 bypasses the auth interceptor: " + pattern);
                Set<org.springframework.web.bind.annotation.RequestMethod> methods = info.getMethodsCondition().getMethods();
                assertFalse(methods.isEmpty(), "Route without an explicit method: " + pattern);
                for (var method : methods) {
                    routes++;
                    String key = method.name() + " " + pattern;
                    if (entry.getValue().hasMethodAnnotation(PublicEndpoint.class)) {
                        annotatedPublic.add(key);
                    }
                    String path = pattern.replaceAll("\\{[^/]+}", "x");
                    int status = mockMvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(method.name()), path))
                        .andReturn().getResponse().getStatus();
                    if (status != 401) {
                        reachableWithoutToken.add(key);
                    }
                }
            }
        }

        assertTrue(routes >= 20, "Only " + routes + " routes were enumerated");
        assertEquals(OPEN_ENDPOINTS, reachableWithoutToken);
        assertEquals(OPEN_ENDPOINTS, annotatedPublic);
    }

    @Test
    @Order(8)
    void theSetupPageIsGoneAndASetupCookieIsUselessAfterCompletion() throws Exception {
        mockMvc.perform(get("/config")).andExpect(status().isNotFound());
        mockMvc.perform(get("/config.html")).andExpect(status().isNotFound());
        mockMvc.perform(get("/config;x")).andExpect(status().isNotFound());
        mockMvc.perform(get("/js/config.js")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/services").cookie(new jakarta.servlet.http.Cookie(SetupInterceptor.SESSION_COOKIE, "anything")))
            .andExpect(status().isUnauthorized());
    }

    private String addClient() {
        pairedClients.addPairedClient("test", "Test client", PairingService.hashToken(TOKEN), Instant.now());
        return TOKEN;
    }
}
