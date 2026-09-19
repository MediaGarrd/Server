package wellatleastitried.mediagarrdServer.services.runner;

import wellatleastitried.mediagarrdServer.services.config.*;

import static wellatleastitried.mediagarrdServer.MediaGarrdUtilities.ServiceConstants.*;

public class SonarrRunner extends ArrRunner {
    public SonarrRunner(AbstractServiceConfig config) {
        super(SUPPORTED_SERVICES.get(Services.SONARR), config);
    }
}
