package wellatleastitried.mediagarrdServer.services.runner;

import java.nio.file.Path;

public interface Runner {

    String getServiceName();

    String getRunnerStartTime();

    String getRunnerEndTime();

    String getRunnerStatus();

    String getRunnerException();

    void run(Path outputDirectory);
}
