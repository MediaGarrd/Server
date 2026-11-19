package wellatleastitried.mediagarrdServer.service;

public class BackupFailedException extends IllegalStateException {

    public BackupFailedException(String message) {
        super(message);
    }
}
