package wellatleastitried.mediagarrdServer.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.util.ReflectionTestUtils;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.repository.TestDatabase;
import wellatleastitried.mediagarrdServer.service.SetupService;

final class FullContextSupport {

    private FullContextSupport() {}

    static Path newDataDir() {
        try {
            return Files.createTempDirectory("mediagarrd-full-context");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static TestDatabase openDatabase(Path dataDir) {
        MediaGarrdProperties properties = new MediaGarrdProperties();
        properties.setDatabasePath(dataDir.resolve("mediagarrd.db").toString());
        return new TestDatabase(properties);
    }

    static void register(DynamicPropertyRegistry registry, Path dataDir) {
        registry.add("mediagarrd.database-path", () -> dataDir.resolve("mediagarrd.db").toString());
        registry.add("mediagarrd.backup-root", () -> dataDir.resolve("backups").toString());
        registry.add("mediagarrd.helper-image", () -> "alpine:3");
        registry.add("mediagarrd.github-api-base-url", () -> "http://127.0.0.1:1");
    }

    static String currentPin(SetupService setup) {
        return ReflectionTestUtils.invokeMethod(setup, "currentPin");
    }
}
