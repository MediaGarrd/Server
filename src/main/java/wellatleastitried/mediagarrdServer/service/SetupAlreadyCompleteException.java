package wellatleastitried.mediagarrdServer.service;

public class SetupAlreadyCompleteException extends IllegalStateException {

    public SetupAlreadyCompleteException() {
        super("Setup is already complete");
    }
}
