package wellatleastitried.mediagarrdServer.services.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import wellatleastitried.mediagarrdServer.services.config.*;

public abstract class ArrRunner extends AbstractLocalCopyRunner {

    private final AbstractServiceConfig config;

    public ArrRunner(String service, AbstractServiceConfig config) {
        super(service);
        this.config = config;
    }

    @Override
    protected List<CopySpec> copySpecs() {
        List<CopySpec> specs = new ArrayList<>();
        specs.add(dir(config.getConfigPath(), "config"));

        Path data = Path.of(config.getPath()).resolve("data");
        if (Files.isDirectory(data)) {
            specs.add(dir(data.toString(), "data"));
        }

        Path compose = Path.of(config.getPath()).resolve("docker-compose.yml");
        if (Files.isRegularFile(compose)) {
            specs.add(file(compose.toString(), "docker-compose.yml"));
        }

        return specs;
    }
}
