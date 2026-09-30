package wellatleastitried.mediagarrdServer.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import wellatleastitried.mediagarrdServer.MediaGarrdConfig;
import wellatleastitried.mediagarrdServer.MediaGarrdProperties;
import wellatleastitried.mediagarrdServer.dto.ConfigUpdateRequest;
import wellatleastitried.mediagarrdServer.dto.ServerConfigDto;

@RestController
@RequestMapping ("/api/v1")
public class ConfigController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigController.class);

    private final MediaGarrdProperties properties;

    public ConfigController(
        MediaGarrdProperties properties
    ) {
        this.properties = properties;
    }

    @GetMapping("/config")
    public ServerConfigDto config() {
        LOGGER.info("GET /api/v1/config");
        return new ServerConfigDto(
            properties.getConfig()
        );
    }

    @PostMapping("/config/update")
    public ServerConfigDto updateConfig(@RequestBody ConfigUpdateRequest request) {
        LOGGER.info("GET /api/v1/config");
        var config = MediaGarrdConfig.buildConfigFromRequest(request);
        properties.setConfig(config);
        return new ServerConfigDto(
            properties.getConfig()
        );
    }
}
