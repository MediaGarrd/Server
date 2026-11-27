package wellatleastitried.mediagarrdServer.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import wellatleastitried.mediagarrdServer.dto.ConfigUpdateRequest;
import wellatleastitried.mediagarrdServer.dto.ServerConfigDto;
import wellatleastitried.mediagarrdServer.model.ServiceDefinition;
import wellatleastitried.mediagarrdServer.service.BackupArchiveService;
import wellatleastitried.mediagarrdServer.service.BackupOrchestratorService;
import wellatleastitried.mediagarrdServer.service.BackupRunLifecycle;
import wellatleastitried.mediagarrdServer.service.RunStatusBroadcaster;
import wellatleastitried.mediagarrdServer.service.ServiceConfigService;
import wellatleastitried.mediagarrdServer.utilities.LogSanitizer;

@RestController
@RequestMapping("/api/v1")
public class ConfigController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigController.class);

    private final BackupArchiveService archiveService;
    private final BackupOrchestratorService orchestrator;
    private final BackupRunLifecycle lifecycle;
    private final ServiceConfigService serviceConfig;
    private final RunStatusBroadcaster broadcaster;

    public ConfigController(
        BackupArchiveService archiveService,
        BackupOrchestratorService orchestrator,
        BackupRunLifecycle lifecycle,
        ServiceConfigService serviceConfig,
        RunStatusBroadcaster broadcaster
    ) {
        this.archiveService = archiveService;
        this.orchestrator = orchestrator;
        this.lifecycle = lifecycle;
        this.serviceConfig = serviceConfig;
        this.broadcaster = broadcaster;
    }

    @SetupSessionAllowed
    @GetMapping("/config")
    public ServerConfigDto config() {
        LOGGER.debug("GET /api/v1/config");
        return currentConfig();
    }

    @SetupSessionAllowed
    @PutMapping("/config")
    public ServerConfigDto updateConfig(@RequestBody ConfigUpdateRequest request) {
        LOGGER.info("PUT /api/v1/config, backupLocation={}, retentionCount={}", LogSanitizer.clean(request.backupLocation()), request.retentionCount());
        return lifecycle.whileIdle(() -> {
            archiveService.updateSettings(request.backupLocation(), request.retentionCount());
            if (request.backupLocation() != null) {
                broadcaster.publishBackupsChanged();
            }
            broadcaster.publishConfigChanged();
            return currentConfig();
        });
    }

    @SetupSessionAllowed
    @GetMapping("/services")
    public List<ServiceDefinition> services() {
        LOGGER.debug("GET /api/v1/services");
        return serviceConfig.list();
    }

    @SetupSessionAllowed
    @PostMapping("/services")
    public ResponseEntity<ServiceDefinition> addService(@RequestBody ServiceDefinition request) {
        ServiceDefinition service = request.validate();
        LOGGER.info("POST /api/v1/services, name={}, paths={}", LogSanitizer.clean(service.name()), LogSanitizer.clean(service.paths()));
        return ResponseEntity.status(HttpStatus.CREATED).body(serviceConfig.add(service));
    }

    @SetupSessionAllowed
    @PutMapping("/services/{name}")
    public ResponseEntity<ServiceDefinition> updateService(@PathVariable String name, @RequestBody ServiceDefinition request) {
        ServiceDefinition service = request.validate();
        LOGGER.info("PUT /api/v1/services/{}, name={}, paths={}", LogSanitizer.clean(name), LogSanitizer.clean(service.name()), LogSanitizer.clean(service.paths()));
        return ResponseEntity.ok(serviceConfig.update(name, service));
    }

    @SetupSessionAllowed
    @DeleteMapping("/services/{name}")
    public ResponseEntity<Void> removeService(@PathVariable String name) {
        LOGGER.info("DELETE /api/v1/services/{}", LogSanitizer.clean(name));
        serviceConfig.remove(name);
        return ResponseEntity.noContent().build();
    }

    private ServerConfigDto currentConfig() {
        return new ServerConfigDto(
            archiveService.rootDirectory().toString(),
            archiveService.retentionCount(),
            orchestrator.getInterval()
        );
    }
}
