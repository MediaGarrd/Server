package wellatleastitried.mediagarrdServer.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogSanitizerTest {

    @Test
    void lineBreaksAndControlCharactersCannotForgeLogLines() {
        String cleaned = LogSanitizer.clean("name\r\n2026-11-20 INFO forged\t\u0000end");

        assertFalse(cleaned.contains("\n") || cleaned.contains("\r") || cleaned.contains("\u0000"));
        assertEquals("name__2026-11-20 INFO forged__end", cleaned);
    }

    @Test
    void longValuesAreCut() {
        assertTrue(LogSanitizer.clean("x".repeat(10_000)).length() < 300);
    }

    @Test
    void nullAndPlainValuesPassThrough() {
        assertEquals("null", LogSanitizer.clean(null));
        assertEquals("/srv/radarr", LogSanitizer.clean("/srv/radarr"));
    }
}
