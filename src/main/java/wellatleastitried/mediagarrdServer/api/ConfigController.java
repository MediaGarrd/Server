package wellatleastitried.mediagarrdServer.api;

import static wellatleastitried.mediagarrdServer.api.ApiExceptionHandler.error;

import java.util.List;
import java.util.Optional;

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
import wellatleastitried.mediagarrdServer.service.DatabaseService;
import wellatleastitried.mediagarrdServer.service.DockerService;

@RestController
@RequestMapping("/api/v1")
public class ConfigController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigController.class);

    private final BackupArchiveService archiveService;
    private final BackupOrchestratorService orchestrator;
    private final DatabaseService db;
    private final DockerService docker;

    public ConfigController(
        BackupArchiveService archiveService,
        BackupOrchestratorService orchestrator,
        DatabaseService db,
        DockerService docker
    ) {
        this.archiveService = archiveService;
        this.orchestrator = orchestrator;
        this.db = db;
        this.docker = docker;
    }

    @GetMapping("/config")
    public ServerConfigDto config() {
        LOGGER.info("GET /api/v1/config");
        return currentConfig();
    }

    @PutMapping("/config")
    public ServerConfigDto updateConfig(@RequestBody ConfigUpdateRequest request) {
        LOGGER.info("PUT /api/v1/config, backupLocation={}, retentionCount={}", request.backupLocation(), request.retentionCount());
        return orchestrator.whileIdle(() -> {
            archiveService.updateSettings(request.backupLocation(), request.retentionCount());
            return currentConfig();
        });
    }

    @GetMapping("/services")
    public List<ServiceDefinition> services() {
        LOGGER.info("GET /api/v1/services");
        return db.fetchServices();
    }

    @PostMapping("/services")
    public ResponseEntity<?> addService(@RequestBody ServiceDefinition request) {
        ServiceDefinition service = request.validate();
        LOGGER.info("POST /api/v1/services, name={}, paths={}", service.name(), service.paths());
        return orchestrator.whileIdle(() -> {
            if (db.fetchService(service.name()).isPresent()) {
                return error(HttpStatus.CONFLICT, "Service already exists: " + service.name());
            }
            db.addService(service);
            return ResponseEntity.status(HttpStatus.CREATED).body(service);
        });
    }

    @PutMapping("/services/{name}")
    public ResponseEntity<?> updateService(@PathVariable String name, @RequestBody ServiceDefinition request) {
        ServiceDefinition service = request.validate();
        LOGGER.info("PUT /api/v1/services/{}, name={}, paths={}", name, service.name(), service.paths());
        return orchestrator.whileIdle(() -> {
            Optional<ServiceDefinition> existing = db.fetchService(name);
            if (existing.isEmpty()) {
                return error(HttpStatus.NOT_FOUND, "Service not found: " + name);
            }
            String currentName = existing.get().name();
            if (!service.name().equalsIgnoreCase(currentName) && db.fetchService(service.name()).isPresent()) {
                return error(HttpStatus.CONFLICT, "Service already exists: " + service.name());
            }
            db.updateService(currentName, service);
            if (!service.name().equals(currentName)) {
                docker.removeContainers(currentName);
            }
            return ResponseEntity.ok(service);
        });
    }

    @DeleteMapping("/services/{name}")
    public ResponseEntity<?> removeService(@PathVariable String name) {
        LOGGER.info("DELETE /api/v1/services/{}", name);
        return orchestrator.whileIdle(() -> {
            Optional<ServiceDefinition> existing = db.fetchService(name);
            if (existing.isEmpty()) {
                return error(HttpStatus.NOT_FOUND, "Service not found: " + name);
            }
            db.removeService(existing.get().name());
            docker.removeContainers(existing.get().name());
            return ResponseEntity.noContent().build();
        });
    }

    private ServerConfigDto currentConfig() {
        return new ServerConfigDto(
            archiveService.rootDirectory().toString(),
            archiveService.retentionCount(),
            orchestrator.getInterval()
        );
    }
}
