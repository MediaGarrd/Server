package wellatleastitried.mediagarrdServer.service;

public class ServiceConflictException extends RuntimeException {

    public ServiceConflictException(String message) {
        super(message);
    }
}
