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
            // ba_employment musí být v seznamu: offline UUID je odvozené ze
            // jména, takže bot vytvořený znovu pod týmž jménem dostane totéž id
            // a zdědil by starou pracovní smlouvu.
            for (String table : List.of("ba_memories", "ba_personalities", "ba_wallets",
                    "ba_transactions", "ba_stats", "ba_settlement_members",
                    "ba_employment", "ba_ext_data", "ba_bots")) {
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
     * Jména známých botů seřazená od naposledy viděných – „štamgasti" pro
     * auto-spawn. Roster je tak stabilní napříč restarty: oživují se titíž
     * boti (identita, vesnice, přátelství), noví se generují jen do počtu.
     *
     * @param limit kolik jmen nejvýš
     * @return jména botů, naposledy připojení první
     */
    public CompletableFuture<List<String>> listKnownBotNames(int limit) {
        return db.async(connection -> {
            List<String> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT name FROM ba_bots ORDER BY last_seen_at DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(rs.getString(1));
                    }
                }
                return result;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Poslední připojení všech botů ({@code ba_bots.last_seen_at}).
     *
     * @return mapa UUID bota → epoch ms posledního připojení
     */
    public CompletableFuture<Map<UUID, Long>> loadLastSeen() {
        return db.async(connection -> {
            Map<UUID, Long> result = new java.util.HashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, last_seen_at FROM ba_bots");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                }
                return result;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
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
     * Zjistí, zda bot už dostal startovní kit.
     *
     * @param botId UUID bota
     * @return {@code true} pokud kit už dostal (nebo řádek neexistuje)
     */
    public CompletableFuture<Boolean> kitGiven(UUID botId) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT kit_given FROM ba_bots WHERE id = ?")) {
                ps.setString(1, botId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    // Chybějící řádek = radši nedávat (kit řeší až upsert při spawnu).
                    return !rs.next() || rs.getInt(1) != 0;
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Označí, že bot startovní kit dostal.
     *
     * @param botId UUID bota
     * @return future dokončení
     */
    public CompletableFuture<Void> markKitGiven(UUID botId) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ba_bots SET kit_given = 1 WHERE id = ?")) {
                ps.setString(1, botId.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
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
     * Smaže jednu vzpomínku podle primárního klíče.
     */
    public CompletableFuture<Void> deleteMemory(long memoryId) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM ba_memories WHERE id = ?")) {
                ps.setLong(1, memoryId);
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

    // --------------------------------------------------------------- vesnice

    /**
     * Řádek vesnice ({@code ba_settlements}).
     *
     * @param id        id vesnice (přiděluje služba, ne DB)
     * @param name      jméno
     * @param world     svět
     * @param x         střed x
     * @param y         střed y
     * @param z         střed z
     * @param founder   UUID zakladatele (text, může být {@code null})
     * @param createdAt čas založení (epoch ms)
     */
    public record SettlementRow(long id, String name, String world, int x, int y, int z,
                                String founder, long createdAt, String announcedTier) {
    }

    /**
     * Řádek členství ({@code ba_settlement_members}).
     *
     * @param botId        UUID bota
     * @param settlementId id vesnice
     * @param joinedAt     čas vstupu (epoch ms)
     * @param plotIndex    index parcely ({@code null} = bez parcely / vlastní)
     * @param plotX        origin parcely x ({@code null} = bez parcely)
     * @param plotY        origin parcely y
     * @param plotZ        origin parcely z
     * @param plotFacing   orientace dveří (název {@code Cardinal})
     */
    public record SettlementMemberRow(UUID botId, long settlementId, long joinedAt,
                                      Integer plotIndex, Integer plotX, Integer plotY,
                                      Integer plotZ, String plotFacing, boolean houseDone) {
    }

    /**
     * Řádek společné stavby sídla ({@code ba_settlement_projects}).
     *
     * @param settlementId id sídla
     * @param kind         druh stavby (název {@code ProjectKind})
     * @param plotIndex    zablokovaná parcela
     * @param x            origin stavby x
     * @param y            origin stavby y
     * @param z            origin stavby z
     * @param facing       orientace (název {@code Cardinal})
     * @param done         stavba dokončena
     * @param state        stav životního cyklu (název {@code ProjectState})
     * @param needed       rozpis materiálu (BOM) – kolik bloků stavba chce
     * @param contributed  kolik bloků už sběrači nanosili
     * @param width        šířka stavby (0 = legacy pevná velikost druhu)
     * @param depth        hloubka stavby (0 = legacy)
     * @param wallHeight   výška zdí (0 = legacy)
     */
    public record SettlementProjectRow(long settlementId, String kind, int plotIndex,
                                       int x, int y, int z, String facing, boolean done,
                                       String state, int needed, int contributed,
                                       int width, int depth, int wallHeight) {
    }

    /**
     * Načte všechny společné stavby sídel.
     */
    public CompletableFuture<List<SettlementProjectRow>> loadSettlementProjects() {
        return db.async(connection -> {
            List<SettlementProjectRow> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT settlement_id, kind, plot_index, x, y, z, facing, done, "
                            + "state, needed, contributed, width, depth, wall_height "
                            + "FROM ba_settlement_projects");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new SettlementProjectRow(rs.getLong(1), rs.getString(2),
                            rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                            rs.getString(7), rs.getInt(8) != 0,
                            rs.getString(9), rs.getInt(10), rs.getInt(11),
                            rs.getInt(12), rs.getInt(13), rs.getInt(14)));
                }
                return result;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Uloží/aktualizuje společnou stavbu sídla.
     */
    public CompletableFuture<Void> upsertSettlementProject(long settlementId, String kind,
                                                           int plotIndex, int x, int y, int z,
                                                           String facing, boolean done,
                                                           String state, int needed,
                                                           int contributed, int width, int depth,
                                                           int wallHeight) {
        String sql = "INSERT INTO ba_settlement_projects(settlement_id, kind, plot_index, "
                + "x, y, z, facing, done, state, needed, contributed, width, depth, wall_height) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + db.dialect().upsertSuffix("settlement_id, kind",
                "plot_index=excluded.plot_index, x=excluded.x, y=excluded.y, "
                        + "z=excluded.z, facing=excluded.facing, done=excluded.done, "
                        + "state=excluded.state, needed=excluded.needed, "
                        + "contributed=excluded.contributed, width=excluded.width, "
                        + "depth=excluded.depth, wall_height=excluded.wall_height");
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, settlementId);
                ps.setString(2, kind);
                ps.setInt(3, plotIndex);
                ps.setInt(4, x);
                ps.setInt(5, y);
                ps.setInt(6, z);
                ps.setString(7, facing);
                ps.setInt(8, done ? 1 : 0);
                ps.setString(9, state);
                ps.setInt(10, needed);
                ps.setInt(11, contributed);
                ps.setInt(12, width);
                ps.setInt(13, depth);
                ps.setInt(14, wallHeight);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Řádek diplomatického vztahu dvou sídel. Dvojice je uspořádaná
     * ({@code settlementA < settlementB}), padlí se počítají po stranách.
     *
     * @param settlementA  menší id dvojice
     * @param settlementB  větší id dvojice
     * @param tension      aktuální napětí
     * @param state        název {@code DiplomacyService.WarState}
     * @param stateSince   od kdy stav platí (epoch ms)
     * @param truceUntil   konec příměří (epoch ms; 0 mimo příměří)
     * @param deathsA      padlí strany A v běžící/poslední válce
     * @param deathsB      padlí strany B
     */
    public record SettlementRelationRow(long settlementA, long settlementB,
                                        double tension, String state, long stateSince,
                                        long truceUntil, int deathsA, int deathsB) {
    }

    /**
     * Načte všechny diplomatické vztahy sídel.
     */
    public CompletableFuture<List<SettlementRelationRow>> loadSettlementRelations() {
        return db.async(connection -> {
            List<SettlementRelationRow> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT settlement_a, settlement_b, tension, state, state_since, "
                            + "truce_until, deaths_a, deaths_b FROM ba_settlement_relations");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new SettlementRelationRow(rs.getLong(1), rs.getLong(2),
                            rs.getDouble(3), rs.getString(4), rs.getLong(5),
                            rs.getLong(6), rs.getInt(7), rs.getInt(8)));
                }
                return result;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Uloží/aktualizuje diplomatický vztah dvou sídel.
     */
    public CompletableFuture<Void> upsertSettlementRelation(SettlementRelationRow row) {
        String sql = "INSERT INTO ba_settlement_relations(settlement_a, settlement_b, "
                + "tension, state, state_since, truce_until, deaths_a, deaths_b, updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)"
                + db.dialect().upsertSuffix("settlement_a, settlement_b",
                "tension=excluded.tension, state=excluded.state, "
                        + "state_since=excluded.state_since, truce_until=excluded.truce_until, "
                        + "deaths_a=excluded.deaths_a, deaths_b=excluded.deaths_b, "
                        + "updated_at=excluded.updated_at");
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, row.settlementA());
                ps.setLong(2, row.settlementB());
                ps.setDouble(3, row.tension());
                ps.setString(4, row.state());
                ps.setLong(5, row.stateSince());
                ps.setLong(6, row.truceUntil());
                ps.setInt(7, row.deathsA());
                ps.setInt(8, row.deathsB());
                ps.setLong(9, System.currentTimeMillis());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Smaže všechny vztahy, ve kterých sídlo figuruje (zaniklé sídlo).
     */
    public CompletableFuture<Void> deleteSettlementRelations(long settlementId) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM ba_settlement_relations "
                            + "WHERE settlement_a = ? OR settlement_b = ?")) {
                ps.setLong(1, settlementId);
                ps.setLong(2, settlementId);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Řádek pracovní smlouvy bota (najímání hráči).
     *
     * @param botId        UUID bota (jedna smlouva na bota)
     * @param employer     UUID zaměstnavatele (hráče)
     * @param employerName jméno zaměstnavatele (pro hlášky bez lookupů)
     * @param kind         název {@code EmploymentService.Kind}
     * @param wage         zaplacená mzda (celkem)
     * @param hiredAt      začátek smlouvy (epoch ms)
     * @param paidUntil    konec zaplaceného období (epoch ms)
     * @param lastDelivery poslední donáška výtěžku (epoch ms; 0 = žádná)
     */
    public record EmploymentRow(UUID botId, UUID employer, String employerName,
                                String kind, double wage, long hiredAt,
                                long paidUntil, long lastDelivery) {
    }

    /**
     * Načte všechny pracovní smlouvy.
     */
    public CompletableFuture<List<EmploymentRow>> loadEmployment() {
        return db.async(connection -> {
            List<EmploymentRow> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT bot_id, employer, employer_name, kind, wage, hired_at, "
                            + "paid_until, last_delivery FROM ba_employment");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new EmploymentRow(UUID.fromString(rs.getString(1)),
                            UUID.fromString(rs.getString(2)), rs.getString(3),
                            rs.getString(4), rs.getDouble(5), rs.getLong(6),
                            rs.getLong(7), rs.getLong(8)));
                }
                return result;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Uloží/aktualizuje pracovní smlouvu bota.
     */
    public CompletableFuture<Void> upsertEmployment(EmploymentRow row) {
        String sql = "INSERT INTO ba_employment(bot_id, employer, employer_name, kind, "
                + "wage, hired_at, paid_until, last_delivery) VALUES (?,?,?,?,?,?,?,?)"
                + db.dialect().upsertSuffix("bot_id",
                "employer=excluded.employer, employer_name=excluded.employer_name, "
                        + "kind=excluded.kind, wage=excluded.wage, "
                        + "hired_at=excluded.hired_at, paid_until=excluded.paid_until, "
                        + "last_delivery=excluded.last_delivery");
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, row.botId().toString());
                ps.setString(2, row.employer().toString());
                ps.setString(3, row.employerName());
                ps.setString(4, row.kind());
                ps.setDouble(5, row.wage());
                ps.setLong(6, row.hiredAt());
                ps.setLong(7, row.paidUntil());
                ps.setLong(8, row.lastDelivery());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Smaže pracovní smlouvu bota (konec/výpověď).
     */
    public CompletableFuture<Void> deleteEmployment(UUID botId) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM ba_employment WHERE bot_id = ?")) {
                ps.setString(1, botId.toString());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Načte všechny vesnice.
     */
    public CompletableFuture<List<SettlementRow>> loadSettlements() {
        return db.async(connection -> {
            List<SettlementRow> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, name, world, x, y, z, founder, created_at, announced_tier "
                            + "FROM ba_settlements");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new SettlementRow(rs.getLong(1), rs.getString(2),
                            rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                            rs.getString(7), rs.getLong(8), rs.getString(9)));
                }
                return result;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Načte všechna členství ve vesnicích.
     */
    public CompletableFuture<List<SettlementMemberRow>> loadSettlementMembers() {
        return db.async(connection -> {
            List<SettlementMemberRow> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT bot_id, settlement_id, joined_at, plot_index, plot_x, plot_y, "
                            + "plot_z, plot_facing, house_done FROM ba_settlement_members");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer plotIndex = (Integer) rs.getObject(4);
                    Integer plotX = (Integer) rs.getObject(5);
                    Integer plotY = (Integer) rs.getObject(6);
                    Integer plotZ = (Integer) rs.getObject(7);
                    result.add(new SettlementMemberRow(UUID.fromString(rs.getString(1)),
                            rs.getLong(2), rs.getLong(3), plotIndex, plotX, plotY, plotZ,
                            rs.getString(8), rs.getInt(9) != 0));
                }
                return result;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Vloží vesnici (id přiděluje volající).
     */
    public CompletableFuture<Void> insertSettlement(long id, String name, String world,
                                                    int x, int y, int z, String founder,
                                                    long createdAt) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ba_settlements(id, name, world, x, y, z, founder, created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, id);
                ps.setString(2, name);
                ps.setString(3, world);
                ps.setInt(4, x);
                ps.setInt(5, y);
                ps.setInt(6, z);
                ps.setString(7, founder);
                ps.setLong(8, createdAt);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Přepíše střed (náves) vesnice – po zániku zakladatelova domu.
     */
    public CompletableFuture<Void> updateSettlementCenter(long id, int x, int y, int z) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ba_settlements SET x = ?, y = ?, z = ? WHERE id = ?")) {
                ps.setInt(1, x);
                ps.setInt(2, y);
                ps.setInt(3, z);
                ps.setLong(4, id);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Smaže vesnici (členství maže služba po jednom při odchodech).
     */
    public CompletableFuture<Void> deleteSettlement(long id) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM ba_settlements WHERE id = ?");
                 PreparedStatement projects = connection.prepareStatement(
                         "DELETE FROM ba_settlement_projects WHERE settlement_id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
                projects.setLong(1, id);
                projects.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Uloží/aktualizuje členství bota ve vesnici.
     */
    public CompletableFuture<Void> upsertSettlementMember(UUID botId, long settlementId,
                                                          long joinedAt, Integer plotIndex,
                                                          Integer plotX, Integer plotY,
                                                          Integer plotZ, String plotFacing,
                                                          boolean houseDone) {
        String sql = "INSERT INTO ba_settlement_members(bot_id, settlement_id, joined_at, "
                + "plot_index, plot_x, plot_y, plot_z, plot_facing, house_done) "
                + "VALUES (?,?,?,?,?,?,?,?,?)"
                + db.dialect().upsertSuffix("bot_id",
                "settlement_id=excluded.settlement_id, joined_at=excluded.joined_at, "
                        + "plot_index=excluded.plot_index, plot_x=excluded.plot_x, "
                        + "plot_y=excluded.plot_y, plot_z=excluded.plot_z, "
                        + "plot_facing=excluded.plot_facing, "
                        + "house_done=excluded.house_done");
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, botId.toString());
                ps.setLong(2, settlementId);
                ps.setLong(3, joinedAt);
                ps.setObject(4, plotIndex);
                ps.setObject(5, plotX);
                ps.setObject(6, plotY);
                ps.setObject(7, plotZ);
                ps.setString(8, plotFacing);
                ps.setInt(9, houseDone ? 1 : 0);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Uloží poslední ohlášený stupeň sídla (potlačení opakovaných hlášek).
     */
    public CompletableFuture<Void> updateSettlementTier(long id, String tier) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ba_settlements SET announced_tier = ? WHERE id = ?")) {
                ps.setString(1, tier);
                ps.setLong(2, id);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Smaže členství bota ve vesnici.
     */
    public CompletableFuture<Void> deleteSettlementMember(UUID botId) {
        return db.async(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM ba_settlement_members WHERE bot_id = ?")) {
                ps.setString(1, botId.toString());
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
