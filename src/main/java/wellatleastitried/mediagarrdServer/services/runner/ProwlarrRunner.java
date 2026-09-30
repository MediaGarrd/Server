package wellatleastitried.mediagarrdServer.services.runner;

import static wellatleastitried.mediagarrdServer.utilities.ServiceConstants.SUPPORTED_SERVICES;

import wellatleastitried.mediagarrdServer.services.config.AbstractServiceConfig;
import wellatleastitried.mediagarrdServer.utilities.ServiceConstants.Services;

public class ProwlarrRunner extends ArrRunner {
    public ProwlarrRunner(AbstractServiceConfig config) {
        super(SUPPORTED_SERVICES.get(Services.PROWLARR), config);
    }
}
