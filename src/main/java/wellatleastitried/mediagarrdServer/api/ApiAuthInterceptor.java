package wellatleastitried.mediagarrdServer.api;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import tools.jackson.databind.json.JsonMapper;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import wellatleastitried.mediagarrdServer.model.PairedClient;
import wellatleastitried.mediagarrdServer.service.PairingService;
import wellatleastitried.mediagarrdServer.service.SetupService;

@Component
public class ApiAuthInterceptor implements HandlerInterceptor {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String CLIENT_ID_ATTRIBUTE = "mediagarrd.clientId";
    private static final String BEARER_PREFIX = "bearer ";
    private static final String SETUP_MESSAGE = "Setup session required, reload /config and enter the PIN from the server logs";
    private static final String UNFINISHED_MESSAGE = "Setup is not finished, open /config and finish it first, then pair this client";
    private static final String MISSING_MESSAGE = "Authorization required, pair this client and send Authorization: Bearer <token>";
    private static final String INVALID_MESSAGE = "Invalid or revoked token, pair this client again";

    private final SetupService setup;
    private final PairingService pairing;

    public ApiAuthInterceptor(SetupService setup, PairingService pairing) {
        this.setup = setup;
        this.pairing = pairing;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        HandlerMethod method = handler instanceof HandlerMethod handlerMethod ? handlerMethod : null;
        PublicEndpoint open = method == null ? null : method.getMethodAnnotation(PublicEndpoint.class);
        boolean setupAllowed = method != null && method.hasMethodAnnotation(SetupSessionAllowed.class);
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (open != null && (!open.tokenOptional() || header == null)) {
            return true;
        }
        boolean setupOpen = setupAllowed && !setup.isCompleted();
        if (setupOpen && setup.canConfigure(SetupInterceptor.sessionToken(request))) {
            return true;
        }
        if (header == null) {
            return reject(response, rejection(setupAllowed, MISSING_MESSAGE));
        }
        String token = bearerToken(header);
        PairedClient client = token == null ? null : pairing.authenticate(token).orElse(null);
        if (client == null) {
            return reject(response, INVALID_MESSAGE);
        }
        request.setAttribute(CLIENT_ID_ATTRIBUTE, client.id());
        return true;
    }

    private String rejection(boolean setupAllowed, String pairedMessage) {
        if (setup.isCompleted()) {
            return pairedMessage;
        }
        return setupAllowed ? SETUP_MESSAGE : UNFINISHED_MESSAGE;
    }

    private static String bearerToken(String header) {
        String value = header.strip();
        if (value.length() <= BEARER_PREFIX.length() || !value.toLowerCase(Locale.ROOT).startsWith(BEARER_PREFIX)) {
            return null;
        }
        return value.substring(BEARER_PREFIX.length()).strip();
    }

    private static boolean reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(JSON.writeValueAsString(Map.of("error", message)));
        return false;
    }
}
