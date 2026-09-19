package wellatleastitried.mediagarrdServer.services.runner;

import wellatleastitried.mediagarrdServer.services.config.*;

import static wellatleastitried.mediagarrdServer.MediaGarrdUtilities.ServiceConstants.*;

public class RadarrRunner extends ArrRunner {
    public RadarrRunner(AbstractServiceConfig config) {
        super(SUPPORTED_SERVICES.get(Services.RADARR), config);
    }
}
