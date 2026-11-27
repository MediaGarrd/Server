package wellatleastitried.mediagarrdServer.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import wellatleastitried.mediagarrdServer.service.PairingService;
import wellatleastitried.mediagarrdServer.service.RunStatusBroadcaster;
import wellatleastitried.mediagarrdServer.service.RunStatusBroadcaster.SubscriberLimitException;

@RestController
@RequestMapping("/api/v1")
public class RunEventsController {

    private static final Logger LOGGER = LoggerFactory.getLogger(RunEventsController.class);

    private final RunStatusBroadcaster broadcaster;
    private final PairingService pairing;

    public RunEventsController(RunStatusBroadcaster broadcaster, PairingService pairing) {
        this.broadcaster = broadcaster;
        this.pairing = pairing;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@RequestAttribute(ApiAuthInterceptor.CLIENT_ID_ATTRIBUTE) String clientId) {
        try {
            SseEmitter emitter = broadcaster.subscribe(clientId);
            if (!pairing.isPaired(clientId)) {
                broadcaster.closeClient(clientId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .body(emitter);
        } catch (SubscriberLimitException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @ExceptionHandler({AsyncRequestTimeoutException.class, AsyncRequestNotUsableException.class})
    public void streamEnded(Exception e, HttpServletResponse response) {
        LOGGER.debug("Event stream ended: {}", e.toString());
    }
}
