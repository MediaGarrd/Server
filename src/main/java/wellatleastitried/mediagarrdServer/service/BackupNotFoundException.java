package wellatleastitried.mediagarrdServer.service;

public class BackupNotFoundException extends RuntimeException {

    public BackupNotFoundException(String message) {
        super(message);
    }
}
