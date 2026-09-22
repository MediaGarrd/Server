package wellatleastitried.mediagarrdServer.services.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import wellatleastitried.mediagarrdServer.services.config.*;

import static wellatleastitried.mediagarrdServer.utilities.ServiceConstants.*;

public class QBittorrentRunner extends AbstractLocalCopyRunner {

    private final QBittorrentServiceConfig config;

    public QBittorrentRunner(QBittorrentServiceConfig config) {
        super(SUPPORTED_SERVICES.get(Services.QBITTORRENT));
        this.config = config;
    }

    @Override
    protected List<CopySpec> copySpecs() {
        List<CopySpec> specs = new ArrayList<>();
        specs.add(dir(config.getConfigPath(), "config"));

        String savedTorrentPath = config.getSavedTorrentsPath();
        if (savedTorrentPath != null && !savedTorrentPath.isEmpty()) {
            specs.add(dir(savedTorrentPath, "saved-torrents"));
        }

        Path compose = Path.of(config.getPath()).resolve("docker-compose.yml");
        if (Files.isRegularFile(compose)) {
            specs.add(file(compose.toString(), "docker-compose.yml"));
        }

        return specs;
    }
}
