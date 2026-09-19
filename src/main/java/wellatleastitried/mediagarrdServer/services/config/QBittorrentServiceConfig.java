package wellatleastitried.mediagarrdServer.services.config;

public class QBittorrentServiceConfig extends AbstractServiceConfig {

    private String savedTorrentsPath;

    public String getSavedTorrentsPath() {
        return savedTorrentsPath;
    }

    public void setSavedTorrentsPath(String savedTorrentsPath) {
        this.savedTorrentsPath = savedTorrentsPath;
    }
}
