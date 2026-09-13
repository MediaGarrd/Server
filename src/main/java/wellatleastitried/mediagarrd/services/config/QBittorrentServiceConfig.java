package wellatleastitried.mediagarrd.services.config;

public class QBittorrentServiceConfig extends AbstractServiceConfig {

    private String savedTorrentsPath;

    // public QBittorrentServiceConfig(ServiceConfig) {}

    public String getSavedTorrentsPath() {
        return savedTorrentsPath;
    }

    public void setSavedTorrentsPath(String savedTorrentsPath) {
        this.savedTorrentsPath = savedTorrentsPath;
    }
}
