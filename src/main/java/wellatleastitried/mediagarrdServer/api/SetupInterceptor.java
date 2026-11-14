package wellatleastitried.mediagarrdServer.api;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import wellatleastitried.mediagarrdServer.service.SetupService;

@Component
public class SetupInterceptor implements HandlerInterceptor {

    static final String SESSION_COOKIE = "mediagarrd-setup";
    private static final List<String> SETUP_PAGE_PATHS = List.of("/config", "/config.html", "/css/", "/js/");
    private static final List<String> CONFIG_API_PATHS = List.of("/api/v1/config", "/api/v1/services", "/api/v1/setup/complete");

    private final SetupService setup;

    public SetupInterceptor(SetupService setup) {
        this.setup = setup;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String path = request.getRequestURI();
        if (matches(path, SETUP_PAGE_PATHS) && setup.isCompleted()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return false;
        }
        if (matches(path, CONFIG_API_PATHS) && !setup.canConfigure(sessionToken(request))) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Setup session required, reload /config and enter the PIN from the server logs\"}");
            return false;
        }
        return true;
    }

    static String sessionToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
            .filter(cookie -> SESSION_COOKIE.equals(cookie.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
    }

    private static boolean matches(String path, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> prefix.endsWith("/")
            ? path.startsWith(prefix)
            : path.equals(prefix) || path.startsWith(prefix + "/"));
    }
}
