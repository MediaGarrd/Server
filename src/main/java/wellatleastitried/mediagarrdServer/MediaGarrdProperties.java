package wellatleastitried.mediagarrdServer;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "mediagarrd")
public class MediaGarrdProperties {

    private Duration backupInterval = Duration.ofHours(12);
    private String backupRoot = "/var/lib/mediagarrd/backups";
    private int retentionCount = 10;
    private String databasePath = "/var/lib/mediagarrd/db/mediagarrd.db";
    private String helperImage = "busybox:stable";
}
