package wellatleastitried.mediagarrdServer.api;

import java.io.EOFException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import jakarta.servlet.http.HttpServletResponse;

import wellatleastitried.mediagarrdServer.service.BackupFailedException;
import wellatleastitried.mediagarrdServer.service.BackupInProgressException;
import wellatleastitried.mediagarrdServer.service.BackupNotFoundException;
import wellatleastitried.mediagarrdServer.service.LockedOutException;
import wellatleastitried.mediagarrdServer.service.PairingException;
import wellatleastitried.mediagarrdServer.service.RateLimitedException;
import wellatleastitried.mediagarrdServer.service.RunRejectedException;
import wellatleastitried.mediagarrdServer.service.ServiceConflictException;
import wellatleastitried.mediagarrdServer.service.ServiceNotFoundException;
import wellatleastitried.mediagarrdServer.utilities.LogSanitizer;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    static final String INTERNAL_ERROR = "Internal error";

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handle(RuntimeException e) {
        HttpStatus status = e instanceof IllegalArgumentException ? HttpStatus.BAD_REQUEST
            : e instanceof RunRejectedException || e instanceof BackupInProgressException ? HttpStatus.CONFLICT
            : HttpStatus.INTERNAL_SERVER_ERROR;
        if (e instanceof BackupFailedException) {
            LOGGER.error("Request failed: {}", e.getMessage());
            return error(status, e.getMessage());
        }
        if (status.is5xxServerError()) {
            LOGGER.error("Request failed", e);
            return error(status, INTERNAL_ERROR);
        }
        LOGGER.warn("Rejected request: {}", LogSanitizer.clean(e.getMessage()));
        return error(status, e.getMessage());
    }

    @ExceptionHandler(ServiceConflictException.class)
    public ResponseEntity<Map<String, String>> serviceConflict(ServiceConflictException e) {
        LOGGER.warn("Rejected request: {}", LogSanitizer.clean(e.getMessage()));
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({ServiceNotFoundException.class, BackupNotFoundException.class})
    public ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
        LOGGER.warn("Rejected request: {}", LogSanitizer.clean(e.getMessage()));
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(LockedOutException.class)
    public ResponseEntity<Map<String, String>> locked(LockedOutException e) {
        LOGGER.warn("Rejected request: {}", e.getMessage());
        return tooManyRequests(e.getMessage(), e.retryAfter());
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Map<String, String>> rateLimited(RateLimitedException e) {
        LOGGER.warn("Rejected request: {}", e.getMessage());
        return tooManyRequests(e.getMessage(), e.retryAfter());
    }

    private static ResponseEntity<Map<String, String>> tooManyRequests(String message, Duration retryAfter) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, retryAfter.toSeconds())))
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("error", message));
    }

    @ExceptionHandler(PairingException.class)
    public ResponseEntity<Map<String, String>> pairingRejected(PairingException e) {
        HttpStatus status = switch (e.reason()) {
            case SETUP_INCOMPLETE -> HttpStatus.CONFLICT;
            case CLOSED -> HttpStatus.FORBIDDEN;
            case INVALID -> HttpStatus.UNAUTHORIZED;
        };
        LOGGER.warn("Rejected pairing request: {}", LogSanitizer.clean(e.getMessage()));
        return error(status, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableBody(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "Request body is missing or not valid JSON");
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<Map<String, String>> badParameter(TypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "Request parameter is not valid");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e, HttpServletResponse response) {
        if (response.isCommitted()) {
            if (isClientGone(e)) {
                LOGGER.debug("Request ended after the response was started: {}", e.toString());
            } else {
                LOGGER.warn("Request failed after the response was started", e);
            }
            return null;
        }
        if (e instanceof ErrorResponse rejected && rejected.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.valueOf(rejected.getStatusCode().value());
            LOGGER.warn("Rejected request: {}", status.getReasonPhrase());
            return ResponseEntity.status(status)
                .headers(rejected.getHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", status.getReasonPhrase()));
        }
        LOGGER.error("Request failed", e);
        HttpStatus status = e instanceof ErrorResponse failed && failed.getStatusCode().is5xxServerError()
            ? HttpStatus.valueOf(failed.getStatusCode().value())
            : HttpStatus.INTERNAL_SERVER_ERROR;
        return error(status, INTERNAL_ERROR);
    }

    private static boolean isClientGone(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof AsyncRequestNotUsableException || cause instanceof AsyncRequestTimeoutException
                || cause instanceof EOFException || cause.getClass().getSimpleName().equals("ClientAbortException")) {
                return true;
            }
        }
        return false;
    }

    static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", Objects.requireNonNullElse(message, status.getReasonPhrase())));
    }
}
