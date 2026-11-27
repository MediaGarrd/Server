package wellatleastitried.mediagarrdServer.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import wellatleastitried.mediagarrdServer.dto.WebhookStatusDto;
import wellatleastitried.mediagarrdServer.dto.WebhookTestDto;
import wellatleastitried.mediagarrdServer.dto.WebhookUpdateRequest;
import wellatleastitried.mediagarrdServer.service.RunStatusBroadcaster;
import wellatleastitried.mediagarrdServer.service.WebhookService;

@RestController
@RequestMapping("/api/v1/notifications/webhook")
public class WebhookController {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookService webhook;
    private final RunStatusBroadcaster broadcaster;

    public WebhookController(WebhookService webhook, RunStatusBroadcaster broadcaster) {
        this.webhook = webhook;
        this.broadcaster = broadcaster;
    }

    @SetupSessionAllowed
    @GetMapping
    public WebhookStatusDto status() {
        LOGGER.debug("GET /api/v1/notifications/webhook");
        return webhook.status();
    }

    @SetupSessionAllowed
    @PutMapping
    public WebhookStatusDto update(@RequestBody WebhookUpdateRequest request) {
        LOGGER.info("PUT /api/v1/notifications/webhook");
        WebhookStatusDto updated = webhook.update(request.url());
        broadcaster.publishConfigChanged();
        return updated;
    }

    @SetupSessionAllowed
    @PostMapping("/test")
    public WebhookTestDto test() {
        LOGGER.info("POST /api/v1/notifications/webhook/test");
        return webhook.test();
    }
}
