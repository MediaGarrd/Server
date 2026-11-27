package wellatleastitried.mediagarrdServer.api;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    public static final int MAX_BODY_BYTES = 64 * 1024;
    static final String MESSAGE = "Request body is too large";
    static final String UNSUPPORTED_CHARSET_MESSAGE = "Unsupported charset in Content-Type";
    private static final Set<String> BODYLESS_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return BODYLESS_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        if (request.getCharacterEncoding() != null && charset(request.getCharacterEncoding()) == null) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(JSON.writeValueAsString(Map.of("error", UNSUPPORTED_CHARSET_MESSAGE)));
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private static Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Connection", "close");
        response.getWriter().write(JSON.writeValueAsString(Map.of("error", MESSAGE)));
    }

    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return source.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("Asynchronous reads are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? null : charset(encoding);
            if (charset == null) {
                charset = StandardCharsets.UTF_8;
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
