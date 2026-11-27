package wellatleastitried.mediagarrdServer.service;

public class BackupInProgressException extends IllegalStateException {

    public BackupInProgressException(String message) {
        super(message);
    }
}
