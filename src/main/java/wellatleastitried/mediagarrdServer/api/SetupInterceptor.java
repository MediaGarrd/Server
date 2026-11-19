package wellatleastitried.mediagarrdServer.api;

import java.io.IOException;
import java.util.Arrays;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import wellatleastitried.mediagarrdServer.service.SetupService;

@Component
public class SetupInterceptor implements HandlerInterceptor {

    static final String SESSION_COOKIE = "mediagarrd-setup";
    public static final String[] SETUP_PAGE_PATTERNS = {"/config", "/config.html", "/css/**", "/js/**"};

    private final SetupService setup;

    public SetupInterceptor(SetupService setup) {
        this.setup = setup;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (setup.isCompleted()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
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
}
