package wellatleastitried.mediagarrdServer.services.runner;

import wellatleastitried.mediagarrdServer.services.config.*;

import static wellatleastitried.mediagarrdServer.MediaGarrdUtilities.ServiceConstants.*;

public class ProwlarrRunner extends ArrRunner {
    public ProwlarrRunner(AbstractServiceConfig config) {
        super(SUPPORTED_SERVICES.get(Services.PROWLARR), config);
    }
}
