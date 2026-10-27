package wellatleastitried.mediagarrdServer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "mediagarrd.backup-root=${java.io.tmpdir}/mediagarrd-binding-test/backups",
    "mediagarrd.database-path=${java.io.tmpdir}/mediagarrd-binding-test/mediagarrd.db",
    "mediagarrd.helper-image=alpine:3"
})
class MediaGarrdPropertiesBindingTest {

    @Autowired
    private MediaGarrdProperties properties;

    @Test
    void bindsApplicationProperties() {
        String tmp = System.getProperty("java.io.tmpdir");

        assertEquals(Duration.ofHours(12), properties.getBackupInterval());
        assertEquals(10, properties.getRetentionCount());
        assertEquals(tmp + "/mediagarrd-binding-test/backups", properties.getBackupRoot());
        assertEquals(tmp + "/mediagarrd-binding-test/mediagarrd.db", properties.getDatabasePath());
        assertEquals("alpine:3", properties.getHelperImage());
    }
}
