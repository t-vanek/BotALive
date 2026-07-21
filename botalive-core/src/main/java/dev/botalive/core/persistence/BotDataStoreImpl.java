package dev.botalive.core.persistence;

import dev.botalive.api.persistence.BotDataStore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Implementace {@link BotDataStore} nad tabulkou {@code ba_ext_data}
 * (migrace v9). Každá operace jde asynchronně na DB vlákno
 * ({@link Database#async}); zápis je upsert přes dialektový {@code ON CONFLICT}.
 */
public final class BotDataStoreImpl implements BotDataStore {

    private final Database db;

    /**
     * @param db databáze
     */
    public BotDataStoreImpl(Database db) {
        this.db = db;
    }

    @Override
    public CompletableFuture<Void> put(UUID botId, String namespace, String key, String value) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        String sql = "INSERT INTO ba_ext_data(bot_id, namespace, data_key, data_value, updated_at) "
                + "VALUES (?, ?, ?, ?, ?)"
                + db.dialect().upsertSuffix("bot_id, namespace, data_key",
                        "data_value = excluded.data_value, updated_at = excluded.updated_at");
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, botId.toString());
                ps.setString(2, namespace);
                ps.setString(3, key);
                ps.setString(4, value);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Optional<String>> get(UUID botId, String namespace, String key) {
        Objects.requireNonNull(botId, "botId");
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT data_value FROM ba_ext_data "
                            + "WHERE bot_id = ? AND namespace = ? AND data_key = ?")) {
                ps.setString(1, botId.toString());
                ps.setString(2, namespace);
                ps.setString(3, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, String>> getNamespace(UUID botId, String namespace) {
        Objects.requireNonNull(botId, "botId");
        return db.async(connection -> {
            Map<String, String> result = new LinkedHashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT data_key, data_value FROM ba_ext_data "
                            + "WHERE bot_id = ? AND namespace = ?")) {
                ps.setString(1, botId.toString());
                ps.setString(2, namespace);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString(1), rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return result;
        });
    }

    @Override
    public CompletableFuture<Void> remove(UUID botId, String namespace, String key) {
        Objects.requireNonNull(botId, "botId");
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM ba_ext_data "
                            + "WHERE bot_id = ? AND namespace = ? AND data_key = ?")) {
                ps.setString(1, botId.toString());
                ps.setString(2, namespace);
                ps.setString(3, key);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }
}
