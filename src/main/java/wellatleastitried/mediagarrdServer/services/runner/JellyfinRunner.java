package wellatleastitried.mediagarrdServer.services.runner;

import static wellatleastitried.mediagarrdServer.utilities.ServiceConstants.SUPPORTED_SERVICES;

import java.util.List;

import wellatleastitried.mediagarrdServer.services.config.AbstractServiceConfig;
import wellatleastitried.mediagarrdServer.utilities.ServiceConstants.Services;

public class JellyfinRunner extends AbstractLocalCopyRunner {

    private final AbstractServiceConfig config;

    public JellyfinRunner(AbstractServiceConfig config) {
        super(SUPPORTED_SERVICES.get(Services.JELLYFIN));
        this.config = config;
    }

    @Override
    protected List<CopySpec> copySpecs() {
        return List.of(dir(config.getConfigPath(), "config"));
    }
}
