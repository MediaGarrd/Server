package wellatleastitried.mediagarrdServer.repository;

import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.ADD_PAIRED_CLIENT;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.COUNT_PAIRED_CLIENTS;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.DELETE_PAIRED_CLIENT;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_PAIRED_CLIENTS;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.FETCH_PAIRED_CLIENT_BY_HASH;
import static wellatleastitried.mediagarrdServer.utilities.database.DatabaseQueries.TOUCH_PAIRED_CLIENT;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import wellatleastitried.mediagarrdServer.model.PairedClient;

@Repository
public class PairedClientRepository {

    private final SqliteDatabase database;

    public PairedClientRepository(SqliteDatabase database) {
        this.database = database;
    }

    public void addPairedClient(String id, String name, String tokenHash, Instant createdAt) {
        database.update("add paired client " + id, ADD_PAIRED_CLIENT, id, name, tokenHash, createdAt.toString());
    }

    public List<PairedClient> fetchPairedClients() {
        return database.query("fetch paired clients", FETCH_PAIRED_CLIENTS, PairedClientRepository::toPairedClient);
    }

    public Optional<PairedClient> fetchPairedClientByTokenHash(String tokenHash) {
        return database.query("fetch paired client by token", FETCH_PAIRED_CLIENT_BY_HASH, PairedClientRepository::toPairedClient, tokenHash)
            .stream().findFirst();
    }

    public boolean removePairedClient(String id) {
        return database.update("remove paired client " + id, DELETE_PAIRED_CLIENT, id) > 0;
    }

    public void touchPairedClient(String id, Instant usedAt) {
        database.update("update paired client " + id, TOUCH_PAIRED_CLIENT, usedAt.toString(), id);
    }

    public int countPairedClients() {
        return database.query("count paired clients", COUNT_PAIRED_CLIENTS, rs -> rs.getInt(1)).get(0);
    }

    private static PairedClient toPairedClient(ResultSet rs) throws SQLException {
        String lastUsed = rs.getString("last_used_at");
        return new PairedClient(rs.getString("id"), rs.getString("name"), Instant.parse(rs.getString("created_at")),
            lastUsed == null ? null : Instant.parse(lastUsed));
    }
}
