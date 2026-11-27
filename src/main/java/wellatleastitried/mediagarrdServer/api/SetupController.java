package wellatleastitried.mediagarrdServer.api;

import static wellatleastitried.mediagarrdServer.api.ApiExceptionHandler.error;
import static wellatleastitried.mediagarrdServer.api.SetupInterceptor.SESSION_COOKIE;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import wellatleastitried.mediagarrdServer.dto.SetupCompleteDto;
import wellatleastitried.mediagarrdServer.dto.SetupSessionRequest;
import wellatleastitried.mediagarrdServer.dto.SetupStatusDto;
import wellatleastitried.mediagarrdServer.repository.ServiceRepository;
import wellatleastitried.mediagarrdServer.service.SetupAlreadyCompleteException;
import wellatleastitried.mediagarrdServer.service.SetupService;

@RestController
@RequestMapping("/api/v1/setup")
public class SetupController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SetupController.class);

    private final SetupService setup;
    private final ServiceRepository services;

    public SetupController(SetupService setup, ServiceRepository services) {
        this.setup = setup;
        this.services = services;
    }

    @PublicEndpoint
    @GetMapping
    public SetupStatusDto status(HttpServletRequest request) {
        return new SetupStatusDto(setup.isCompleted(), setup.canConfigure(SetupInterceptor.sessionToken(request)));
    }

    @PublicEndpoint
    @PostMapping("/session")
    public ResponseEntity<?> startSession(@RequestBody SetupSessionRequest request, HttpServletRequest http) {
        LOGGER.info("POST /api/v1/setup/session");
        if (setup.isCompleted()) {
            return error(HttpStatus.GONE, "Setup is already complete");
        }
        Optional<String> session = setup.startSession(request.pin());
        if (session.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "Invalid PIN, check the server logs for the current PIN");
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie(session.get(), -1, http).toString()).build();
    }

    @SetupSessionAllowed
    @PostMapping("/complete")
    public ResponseEntity<?> complete(HttpServletRequest http) {
        LOGGER.info("POST /api/v1/setup/complete");
        if (setup.isCompleted()) {
            return error(HttpStatus.GONE, "Setup is already complete");
        }
        if (services.fetchServices().isEmpty()) {
            return error(HttpStatus.CONFLICT, "Add at least one service before finishing setup");
        }
        String pairingPin;
        try {
            pairingPin = setup.complete();
        } catch (SetupAlreadyCompleteException e) {
            return error(HttpStatus.GONE, "Setup is already complete");
        }
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie("", 0, http).toString()).body(new SetupCompleteDto(pairingPin));
    }

    private static boolean secure(HttpServletRequest request) {
        return request.isSecure() || forwardedHttps(request.getHeader("X-Forwarded-Proto"));
    }

    private static boolean forwardedHttps(String header) {
        return header != null && "https".equalsIgnoreCase(header.split(",")[0].strip());
    }

    private static ResponseCookie cookie(String value, long maxAgeSeconds, HttpServletRequest request) {
        return ResponseCookie.from(SESSION_COOKIE, value)
            .httpOnly(true)
            .secure(secure(request))
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAgeSeconds)
            .build();
    }
}
