package wellatleastitried.mediagarrdServer.services.runner;

import static wellatleastitried.mediagarrdServer.utilities.ServiceConstants.SUPPORTED_SERVICES;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import wellatleastitried.mediagarrdServer.services.config.AbstractServiceConfig;
import wellatleastitried.mediagarrdServer.utilities.ServiceConstants.Services;

// Tdarr has a different structure than radarr/sonarr/prowlarr, so it will
// use the AbstractLocalCopyRunner instead of ArrRunner.
public class TdarrRunner extends AbstractLocalCopyRunner {

    private final AbstractServiceConfig config;

    public TdarrRunner(AbstractServiceConfig config) {
        super(SUPPORTED_SERVICES.get(Services.TDARR));
        this.config = config;
    }

    @Override
    protected List<CopySpec> copySpecs() {
        List<CopySpec> specs = new ArrayList<>();
        specs.add(dir(config.getConfigPath(), "configs"));

        Path server = Path.of(config.getPath()).resolve("server");
        if (Files.isDirectory(server)) {
            specs.add(dir(server.toString(), "server"));
        }

        Path logs = Path.of(config.getPath()).resolve("logs");
        if (Files.isDirectory(logs)) {
            specs.add(dir(logs.toString(), "logs"));
        }

        Path compose = Path.of(config.getPath()).resolve("docker-compose.yml");
        if (Files.isRegularFile(compose)) {
            specs.add(file(compose.toString(), "docker-compose.yml"));
        }

        return specs;
    }
}
