package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;

@Timeout(30)
class ZipCompressionBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void failsStartupForALevelOutOfRange() {
        runner.withPropertyValues("mediagarrd.compression-level=12").run(context -> {
            assertTrue(context.getStartupFailure() != null);
            assertTrue(rootMessages(context.getStartupFailure()).contains("mediagarrd.compression-level must be between 0 and 9, was 12"));
        });
    }

    private static String rootMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }

    @Configuration
    @EnableConfigurationProperties(MediaGarrdProperties.class)
    @Import(ZipCompression.class)
    static class Config {
    }
}
