package wellatleastitried.mediagarrdServer.api;

import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import wellatleastitried.mediagarrdServer.model.PairedClient;
import wellatleastitried.mediagarrdServer.service.PairingService;

final class ApiTestAuth {

    static final String TOKEN = "test-token";
    static final PairedClient CLIENT = new PairedClient("c1", "Test client", Instant.parse("2026-11-19T10:00:00Z"), null);

    private ApiTestAuth() {}

    static void accept(PairingService pairing) {
        when(pairing.authenticate(TOKEN)).thenReturn(Optional.of(CLIENT));
        when(pairing.isPaired(CLIENT.id())).thenReturn(true);
    }

    static MockHttpServletRequestBuilder get(String url, Object... vars) {
        return withToken(MockMvcRequestBuilders.get(url, vars));
    }

    static MockHttpServletRequestBuilder post(String url, Object... vars) {
        return withToken(MockMvcRequestBuilders.post(url, vars));
    }

    static MockHttpServletRequestBuilder put(String url, Object... vars) {
        return withToken(MockMvcRequestBuilders.put(url, vars));
    }

    static MockHttpServletRequestBuilder delete(String url, Object... vars) {
        return withToken(MockMvcRequestBuilders.delete(url, vars));
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
    }
}
