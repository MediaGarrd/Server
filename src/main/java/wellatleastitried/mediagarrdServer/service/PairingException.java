package wellatleastitried.mediagarrdServer.service;

public class PairingException extends RuntimeException {

    public enum Reason { SETUP_INCOMPLETE, CLOSED, INVALID }

    private final Reason reason;

    public PairingException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
