package wellatleastitried.mediagarrdServer;

import java.time.Duration;
import java.util.EnumMap;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;
import wellatleastitried.mediagarrdServer.services.config.AbstractServiceConfig;
import wellatleastitried.mediagarrdServer.utilities.ServiceConstants.Services;

@Getter
@Setter
@ConfigurationProperties(prefix = "mediagarrd")
public class MediaGarrdProperties {

    private Duration backupInterval = Duration.ofHours(12);
    private String backupRoot = "./data/server/backups";
    private int retentionCount = 10;
    private ServicesProperties services = new ServicesProperties();
    private MediaGarrdConfig config = new MediaGarrdConfig();

    public EnumMap<Services, AbstractServiceConfig> getServiceConfigs() {
        return services.toRuntimeConfigs();
    }


}
