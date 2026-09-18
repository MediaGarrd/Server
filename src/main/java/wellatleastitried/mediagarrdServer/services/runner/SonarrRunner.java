package wellatleastitried.mediagarrdServer.services.runner;

import java.util.List;

import wellatleastitried.mediagarrdServer.services.config.*;

import static wellatleastitried.mediagarrd.MediaGarrdUtilities.ServiceConstants.*;

public class SonarrRunner extends AbstractLocalCopyRunner {

    private final AbstractServiceConfig config;

    public SonarrRunner(AbstractServiceConfig config) {
        super(SUPPORTED_SERVICES.get(Services.SONARR));
        this.config = config;
    }

    @Override
    protected List<CopySpec> copySpecs() {
        return List.of(dir(config.getPath(), "appdata"));
    }
}
