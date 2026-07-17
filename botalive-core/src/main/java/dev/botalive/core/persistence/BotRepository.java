package dev.botalive.core.persistence;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.personality.PersonalityGenerator;
import dev.botalive.core.personality.PersonalityImpl;

import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repozitář persistentních dat botů (identita, osobnost, paměť, peněženka, statistiky).
 *
 * <p>Jedna třída pro celý agregát bota – všechny tabulky sdílí klíč
 * {@code bot_id} a načítají/ukládají se společně při spawnu/odpojení bota.
 * Všechny metody jsou asynchronní (běží na DB vlákně {@link Database}).</p>
 */
public final class BotRepository {

    private static final Gson GSON = new Gson();
    private static final Type STRING_MAP = new TypeToken<Map<String, String>>() { }.getType();
    private static final Type TRAIT_MAP = new TypeToken<Map<String, Double>>() { }.getType();

    private final Database db;

    /**
     * @param db databáze
     */
    public BotRepository(Database db) {
        this.db = db;
    }

    // ------------------------------------------------------------------ boti

    /**
     * Uloží/aktualizuje základní záznam bota.
     */
    public CompletableFuture<Void> upsertBot(UUID id, String name, String world,
                                             double x, double y, double z) {
        String sql = "INSERT INTO ba_bots(id, name, created_at, last_seen_at, world, x, y, z) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                + db.dialect().upsertSuffix("id",
                "last_seen_at=excluded.last_seen_at, world=excluded.world, "
                        + "x=excluded.x, y=excluded.y, z=excluded.z");
        long now = System.currentTimeMillis();
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, id.toString());
                ps.setString(2, name);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.setString(5, world);
                ps.setDouble(6, x);
                ps.setDouble(7, y);
                ps.setDouble(8, z);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Smaže bota a všechna jeho data.
     */
    public CompletableFuture<Void> purgeBot(UUID id) {
        return db.async(connection -> {
            String botId = id.toString();
            for (String table : List.of("ba_memories", "ba_personalities", "ba_wallets",
                    "ba_transactions", "ba_stats", "ba_bots")) {
                String column = table.equals("ba_bots") ? "id" : "bot_id";
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE " + column + " = ?")) {
                    ps.setString(1, botId);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
            return null;
        });
    }

    /**
     * Načte uloženou profesi bota.
     *
     * @param botId UUID bota
     * @return název role, nebo {@code null} pokud není uložena
     */
    public CompletableFuture<String> loadRole(UUID botId) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT role FROM ba_bots WHERE id = ?")) {
                ps.setString(1, botId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Uloží profesi bota.
     *
     * @param botId UUID bota
     * @param role  název role
     */
    public CompletableFuture<Void> saveRole(UUID botId, String role) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ba_bots SET role = ? WHERE id = ?")) {
                ps.setString(1, role);
                ps.setString(2, botId.toString());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ------------------------------------------------------------- osobnosti

    /**
     * Načte osobnost bota; pokud neexistuje, vygeneruje ji z {@code fallbackSeed}
     * a uloží.
     *
     * @param botId        UUID bota
     * @param fallbackSeed seed pro novou osobnost
     * @return future s osobností
     */
    public CompletableFuture<Personality> loadOrCreatePersonality(UUID botId, long fallbackSeed) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT seed, traits_json FROM ba_personalities WHERE bot_id = ?")) {
                ps.setString(1, botId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long seed = rs.getLong(1);
                        Map<String, Double> raw = GSON.fromJson(rs.getString(2), TRAIT_MAP);
                        Map<Trait, Double> traits = new EnumMap<>(Trait.class);
                        raw.forEach((k, v) -> traits.put(Trait.valueOf(k), v));
                        return new PersonalityImpl(seed, traits);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }

            Personality personality = PersonalityGenerator.generate(fallbackSeed);
            Map<String, Double> raw = new java.util.LinkedHashMap<>();
            personality.traits().forEach((k, v) -> raw.put(k.name(), v));
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ba_personalities(bot_id, seed, archetype, traits_json) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, botId.toString());
                ps.setLong(2, personality.seed());
                ps.setString(3, personality.archetype());
                ps.setString(4, GSON.toJson(raw));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return personality;
        });
    }

    /**
     * Uloží aktuální (vyvinuté) rysy a archetyp osobnosti.
     *
     * <p>Osobnost se vývojem podle prožitků mění – ukládá se celý stav rysů,
     * takže po restartu bot pokračuje s povahou, kterou si vypěstoval.</p>
     *
     * @param botId       UUID bota
     * @param personality osobnost s aktuálními hodnotami
     * @return future dokončení zápisu
     */
    public CompletableFuture<Void> savePersonalityTraits(UUID botId, Personality personality) {
        Map<String, Double> raw = new java.util.LinkedHashMap<>();
        personality.traits().forEach((k, v) -> raw.put(k.name(), v));
        String json = GSON.toJson(raw);
        String archetype = personality.archetype();
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ba_personalities SET traits_json = ?, archetype = ? WHERE bot_id = ?")) {
                ps.setString(1, json);
                ps.setString(2, archetype);
                ps.setString(3, botId.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    // ----------------------------------------------------------------- paměť

    /**
     * Načte všechny vzpomínky bota.
     */
    public CompletableFuture<List<MemoryRecord>> loadMemories(UUID botId) {
        return db.async(connection -> {
            List<MemoryRecord> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, kind, world, x, y, z, subject, data_json, importance, created_at, updated_at "
                            + "FROM ba_memories WHERE bot_id = ?")) {
                ps.setString(1, botId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String subject = rs.getString(7);
                        String dataJson = rs.getString(8);
                        Map<String, String> data = dataJson == null || dataJson.isBlank()
                                ? Map.of()
                                : GSON.fromJson(dataJson, STRING_MAP);
                        result.add(new MemoryRecord(
                                rs.getLong(1), botId, MemoryKind.valueOf(rs.getString(2)),
                                rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                                subject == null ? null : UUID.fromString(subject),
                                data, rs.getDouble(9), rs.getLong(10), rs.getLong(11)));
                    }
                }
                return result;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Vloží vzpomínku a vrátí ji s přiděleným primárním klíčem.
     */
    public CompletableFuture<MemoryRecord> insertMemory(MemoryRecord record) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ba_memories(bot_id, kind, world, x, y, z, subject, data_json, "
                            + "importance, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, record.botId().toString());
                ps.setString(2, record.kind().name());
                ps.setString(3, record.world());
                ps.setInt(4, record.x());
                ps.setInt(5, record.y());
                ps.setInt(6, record.z());
                ps.setString(7, record.subject() == null ? null : record.subject().toString());
                ps.setString(8, record.data().isEmpty() ? null : GSON.toJson(record.data()));
                ps.setDouble(9, record.importance());
                ps.setLong(10, record.createdAt());
                ps.setLong(11, record.updatedAt());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    long id = keys.next() ? keys.getLong(1) : 0;
                    return record.withId(id);
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Aktualizuje důležitost a čas oživené vzpomínky.
     */
    public CompletableFuture<Void> touchMemory(long memoryId, double importance, long updatedAt) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ba_memories SET importance = ?, updated_at = ? WHERE id = ?")) {
                ps.setDouble(1, importance);
                ps.setLong(2, updatedAt);
                ps.setLong(3, memoryId);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Smaže vzpomínky bota dané kategorie.
     */
    public CompletableFuture<Void> deleteMemories(UUID botId, MemoryKind kind) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM ba_memories WHERE bot_id = ? AND kind = ?")) {
                ps.setString(1, botId.toString());
                ps.setString(2, kind.name());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ------------------------------------------------------------- peněženky

    /**
     * Načte zůstatek peněženky (nebo založí s počátečním zůstatkem).
     */
    public CompletableFuture<Double> loadOrCreateWallet(UUID botId, double startingBalance) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT balance FROM ba_wallets WHERE bot_id = ?")) {
                ps.setString(1, botId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble(1);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ba_wallets(bot_id, balance, updated_at) VALUES (?, ?, ?)")) {
                ps.setString(1, botId.toString());
                ps.setDouble(2, startingBalance);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return startingBalance;
        });
    }

    /**
     * Uloží zůstatek a transakci do logu.
     */
    public CompletableFuture<Void> saveWallet(UUID botId, double balance, double amount, String reason) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ba_wallets SET balance = ?, updated_at = ? WHERE bot_id = ?")) {
                ps.setDouble(1, balance);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, botId.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ba_transactions(bot_id, amount, reason, created_at) VALUES (?,?,?,?)")) {
                ps.setString(1, botId.toString());
                ps.setDouble(2, amount);
                ps.setString(3, reason);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ------------------------------------------------------------ statistiky

    /**
     * Přičte statistiky bota (delta hodnoty).
     */
    public CompletableFuture<Void> addStats(UUID botId, long mined, long placed, long deaths,
                                            long kills, long messages, long distanceCm, long playtimeS) {
        String sql = "INSERT INTO ba_stats(bot_id, blocks_mined, blocks_placed, deaths, kills, "
                + "messages_sent, distance_cm, playtime_seconds) VALUES (?,?,?,?,?,?,?,?)"
                + db.dialect().upsertSuffix("bot_id",
                "blocks_mined=ba_stats.blocks_mined+excluded.blocks_mined, "
                        + "blocks_placed=ba_stats.blocks_placed+excluded.blocks_placed, "
                        + "deaths=ba_stats.deaths+excluded.deaths, "
                        + "kills=ba_stats.kills+excluded.kills, "
                        + "messages_sent=ba_stats.messages_sent+excluded.messages_sent, "
                        + "distance_cm=ba_stats.distance_cm+excluded.distance_cm, "
                        + "playtime_seconds=ba_stats.playtime_seconds+excluded.playtime_seconds");
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, botId.toString());
                ps.setLong(2, mined);
                ps.setLong(3, placed);
                ps.setLong(4, deaths);
                ps.setLong(5, kills);
                ps.setLong(6, messages);
                ps.setLong(7, distanceCm);
                ps.setLong(8, playtimeS);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Načte statistiky bota jako mapu sloupec → hodnota.
     */
    public CompletableFuture<Map<String, Long>> loadStats(UUID botId) {
        return db.async(connection -> {
            Map<String, Long> stats = new java.util.LinkedHashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT blocks_mined, blocks_placed, deaths, kills, messages_sent, "
                            + "distance_cm, playtime_seconds FROM ba_stats WHERE bot_id = ?")) {
                ps.setString(1, botId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        stats.put("blocks_mined", rs.getLong(1));
                        stats.put("blocks_placed", rs.getLong(2));
                        stats.put("deaths", rs.getLong(3));
                        stats.put("kills", rs.getLong(4));
                        stats.put("messages_sent", rs.getLong(5));
                        stats.put("distance_cm", rs.getLong(6));
                        stats.put("playtime_seconds", rs.getLong(7));
                    }
                }
                return stats;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
