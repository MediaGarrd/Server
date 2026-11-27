package wellatleastitried.mediagarrdServer.service;

public class RunRejectedException extends IllegalStateException {

    public RunRejectedException(String message) {
        super(message);
    }
}
