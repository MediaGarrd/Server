package wellatleastitried.mediagarrdServer.api;

import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import wellatleastitried.mediagarrdServer.service.BackupOrchestratorService.BackupInProgressException;
import wellatleastitried.mediagarrdServer.service.BackupOrchestratorService.RunRejectedException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handle(RuntimeException e) {
        HttpStatus status = e instanceof IllegalArgumentException ? HttpStatus.BAD_REQUEST
            : e instanceof RunRejectedException || e instanceof BackupInProgressException ? HttpStatus.CONFLICT
            : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is5xxServerError()) {
            LOGGER.error("Request failed: {}", e.getMessage());
        } else {
            LOGGER.warn("Rejected request: {}", e.getMessage());
        }
        return error(status, e.getMessage());
    }

    static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Objects.requireNonNullElse(message, status.getReasonPhrase())));
    }
}
