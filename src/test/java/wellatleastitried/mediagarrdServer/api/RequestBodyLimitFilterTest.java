package wellatleastitried.mediagarrdServer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class RequestBodyLimitFilterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter();

    private MockHttpServletRequest post(String characterEncoding) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pair");
        request.setContentType("application/json");
        request.setCharacterEncoding(characterEncoding);
        request.setContent("{\"pin\":\"000000\"}".getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void anUnknownCharsetAnswers400WithTheErrorShapeAndNeverReachesTheController() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(post("nope-8"), response, chain);

        assertEquals(400, response.getStatus());
        assertEquals(Map.of("error", RequestBodyLimitFilter.UNSUPPORTED_CHARSET_MESSAGE), JSON.readValue(response.getContentAsString(), Map.class));
        assertNull(chain.getRequest());
    }

    @Test
    void anIllegalCharsetNameAnswers400() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post("bad name!"), response, new MockFilterChain());

        assertEquals(400, response.getStatus());
    }

    @Test
    void aKnownCharsetIsReadWithThatCharset() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> body = new AtomicReference<>();

        filter.doFilter(post("ISO-8859-1"), response, (request, servletResponse) -> {
            body.set(((HttpServletRequest) request).getReader().readLine());
        });

        assertEquals(200, response.getStatus());
        assertEquals("{\"pin\":\"000000\"}", body.get());
    }
}
