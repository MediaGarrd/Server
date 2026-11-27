package wellatleastitried.mediagarrdServer.api;

import java.util.Map;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<Map<String, String>> error(HttpServletRequest request) {
        HttpStatus status = statusOf(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
        return ApiExceptionHandler.error(status, status.is4xxClientError() ? status.getReasonPhrase() : ApiExceptionHandler.INTERNAL_ERROR);
    }

    private static HttpStatus statusOf(Object code) {
        if (code == null) {
            return HttpStatus.NOT_FOUND;
        }
        HttpStatus status = code instanceof Integer value ? HttpStatus.resolve(value) : null;
        return status != null && status.isError() ? status : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
