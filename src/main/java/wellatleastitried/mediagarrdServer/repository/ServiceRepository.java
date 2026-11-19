package wellatleastitried.mediagarrdServer.repository;

import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.ADD_SERVICE;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.DELETE_SERVICE;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_ALL_SERVICES;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_SERVICE_BY_NAME;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.UPDATE_SERVICE;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import wellatleastitried.mediagarrdServer.model.ServiceDefinition;

@Repository
public class ServiceRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> PATH_LIST = new TypeReference<>() {};

    private final SqliteDatabase database;

    public ServiceRepository(SqliteDatabase database) {
        this.database = database;
    }

    public List<ServiceDefinition> fetchServices() {
        return database.query("fetch configured services", FETCH_ALL_SERVICES, ServiceRepository::toServiceDefinition);
    }

    public Optional<ServiceDefinition> fetchService(String name) {
        return database.query("fetch service " + name, FETCH_SERVICE_BY_NAME, ServiceRepository::toServiceDefinition, name)
            .stream().findFirst();
    }

    public void addService(ServiceDefinition service) {
        database.update("add service " + service.name(), ADD_SERVICE, service.name(), toJson(service.paths()));
    }

    public boolean updateService(String name, ServiceDefinition service) {
        return database.update("update service " + name, UPDATE_SERVICE, service.name(), toJson(service.paths()), name) > 0;
    }

    public boolean removeService(String name) {
        return database.update("remove service " + name, DELETE_SERVICE, name) > 0;
    }

    private static ServiceDefinition toServiceDefinition(ResultSet rs) throws SQLException {
        try {
            return new ServiceDefinition(rs.getString("name"), JSON.readValue(rs.getString("paths"), PATH_LIST));
        } catch (JacksonException e) {
            throw new SQLException("Corrupt paths for service " + rs.getString("name"), e);
        }
    }

    private static String toJson(List<String> paths) {
        try {
            return JSON.writeValueAsString(paths);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize service paths", e);
        }
    }
}
