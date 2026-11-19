package wellatleastitried.mediagarrdServer.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;

class NoPolymorphicTypingTest {

    private static final Pattern TYPING = Pattern.compile("JsonTypeInfo|DefaultTyping|activateDefaultTyping|enableDefaultTyping|PolymorphicTypeValidator");

    @Test
    void mainCodeNeverEnablesPolymorphicDeserialization() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<Path> offenders = files.filter(path -> path.toString().endsWith(".java"))
                .filter(path -> TYPING.matcher(read(path)).find())
                .toList();
            assertTrue(offenders.isEmpty(), "Polymorphic typing found in " + offenders);
        }
    }

    @Test
    void aTypeHintInARequestBodyIsIgnoredNotInstantiated() {
        ServiceDefinition parsed = JsonMapper.builder().build().readValue(
            "{\"@class\":\"java.lang.ProcessBuilder\",\"name\":\"radarr\",\"paths\":[\"/srv\"]}", ServiceDefinition.class);

        assertEquals("radarr", parsed.name());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
