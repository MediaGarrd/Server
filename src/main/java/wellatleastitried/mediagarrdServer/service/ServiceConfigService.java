package wellatleastitried.mediagarrdServer.service;

import java.util.List;

import org.springframework.stereotype.Service;

import wellatleastitried.mediagarrdServer.model.ServiceDefinition;
import wellatleastitried.mediagarrdServer.repository.ServiceRepository;

@Service
public class ServiceConfigService {

    private final ServiceRepository services;
    private final DockerService docker;
    private final BackupOrchestratorService orchestrator;
    private final BackupRunLifecycle lifecycle;
    private final RunStatusBroadcaster broadcaster;

    public ServiceConfigService(
        ServiceRepository services,
        DockerService docker,
        BackupOrchestratorService orchestrator,
        BackupRunLifecycle lifecycle,
        RunStatusBroadcaster broadcaster
    ) {
        this.services = services;
        this.docker = docker;
        this.orchestrator = orchestrator;
        this.lifecycle = lifecycle;
        this.broadcaster = broadcaster;
    }

    public List<ServiceDefinition> list() {
        return services.fetchServices();
    }

    public ServiceDefinition add(ServiceDefinition service) {
        return lifecycle.whileIdle(() -> {
            requireNameFree(service.name());
            services.addService(service);
            orchestrator.servicesChanged();
            broadcaster.publishConfigChanged();
            return service;
        });
    }

    public ServiceDefinition update(String name, ServiceDefinition service) {
        return lifecycle.whileIdle(() -> {
            String currentName = require(name).name();
            if (!service.name().equalsIgnoreCase(currentName)) {
                requireNameFree(service.name());
            }
            services.updateService(currentName, service);
            if (!service.name().equals(currentName)) {
                docker.removeContainers(currentName);
            }
            orchestrator.servicesChanged();
            broadcaster.publishConfigChanged();
            return service;
        });
    }

    public void remove(String name) {
        lifecycle.whileIdle(() -> {
            String currentName = require(name).name();
            services.removeService(currentName);
            docker.removeContainers(currentName);
            broadcaster.publishConfigChanged();
            return null;
        });
    }

    private ServiceDefinition require(String name) {
        return services.fetchService(name).orElseThrow(() -> new ServiceNotFoundException("Service not found: " + name));
    }

    private void requireNameFree(String name) {
        if (services.fetchService(name).isPresent()) {
            throw new ServiceConflictException("Service already exists: " + name);
        }
    }
}
