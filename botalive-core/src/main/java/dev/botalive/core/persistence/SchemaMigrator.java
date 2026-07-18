package dev.botalive.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Verzované migrace schématu.
 *
 * <p>Každá migrace má číslo; aplikují se v pořadí a aktuální verze se drží
 * v {@code ba_schema_version}. Nová verze pluginu tak může bezpečně rozšířit
 * schéma bez ruční intervence administrátora.</p>
 */
public final class SchemaMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaMigrator.class);

    private final Database database;

    /**
     * @param database databáze
     */
    public SchemaMigrator(Database database) {
        this.database = database;
    }

    /**
     * Aplikuje všechny chybějící migrace. Volá se synchronně při startu.
     */
    public void migrate() {
        database.sync(connection -> {
            try {
                int current = currentVersion(connection);
                List<List<String>> migrations = migrations(database.dialect());
                for (int version = current + 1; version <= migrations.size(); version++) {
                    LOG.info("Aplikuji migraci schématu v{}", version);
                    try (Statement statement = connection.createStatement()) {
                        for (String sql : migrations.get(version - 1)) {
                            statement.executeUpdate(sql);
                        }
                        statement.executeUpdate("DELETE FROM ba_schema_version");
                        statement.executeUpdate("INSERT INTO ba_schema_version(version) VALUES (" + version + ")");
                    }
                }
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException("Migrace schématu selhala", e);
            }
        });
    }

    private int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS ba_schema_version (version INTEGER NOT NULL)");
            try (ResultSet rs = statement.executeQuery("SELECT MAX(version) FROM ba_schema_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Definice migrací – index v seznamu = verze - 1. */
    private static List<List<String>> migrations(SqlDialect dialect) {
        return List.of(
                // v1 – základní schéma
                List.of(
                        """
                        CREATE TABLE IF NOT EXISTS ba_bots (
                            id TEXT PRIMARY KEY,
                            name TEXT NOT NULL UNIQUE,
                            created_at BIGINT NOT NULL,
                            last_seen_at BIGINT NOT NULL,
                            world TEXT,
                            x DOUBLE PRECISION NOT NULL DEFAULT 0,
                            y DOUBLE PRECISION NOT NULL DEFAULT 0,
                            z DOUBLE PRECISION NOT NULL DEFAULT 0
                        )
                        """,
                        """
                        CREATE TABLE IF NOT EXISTS ba_personalities (
                            bot_id TEXT PRIMARY KEY,
                            seed BIGINT NOT NULL,
                            archetype TEXT NOT NULL,
                            traits_json TEXT NOT NULL
                        )
                        """,
                        "CREATE TABLE IF NOT EXISTS ba_memories ("
                                + "id " + dialect.autoIncrementPk() + ", "
                                + "bot_id TEXT NOT NULL, "
                                + "kind TEXT NOT NULL, "
                                + "world TEXT, "
                                + "x INTEGER NOT NULL DEFAULT 0, "
                                + "y INTEGER NOT NULL DEFAULT 0, "
                                + "z INTEGER NOT NULL DEFAULT 0, "
                                + "subject TEXT, "
                                + "data_json TEXT, "
                                + "importance DOUBLE PRECISION NOT NULL DEFAULT 0.5, "
                                + "created_at BIGINT NOT NULL, "
                                + "updated_at BIGINT NOT NULL)",
                        "CREATE INDEX IF NOT EXISTS idx_ba_memories_bot_kind ON ba_memories(bot_id, kind)",
                        """
                        CREATE TABLE IF NOT EXISTS ba_wallets (
                            bot_id TEXT PRIMARY KEY,
                            balance DOUBLE PRECISION NOT NULL DEFAULT 0,
                            updated_at BIGINT NOT NULL
                        )
                        """,
                        "CREATE TABLE IF NOT EXISTS ba_transactions ("
                                + "id " + dialect.autoIncrementPk() + ", "
                                + "bot_id TEXT NOT NULL, "
                                + "amount DOUBLE PRECISION NOT NULL, "
                                + "reason TEXT, "
                                + "created_at BIGINT NOT NULL)",
                        """
                        CREATE TABLE IF NOT EXISTS ba_stats (
                            bot_id TEXT PRIMARY KEY,
                            blocks_mined BIGINT NOT NULL DEFAULT 0,
                            blocks_placed BIGINT NOT NULL DEFAULT 0,
                            deaths BIGINT NOT NULL DEFAULT 0,
                            kills BIGINT NOT NULL DEFAULT 0,
                            messages_sent BIGINT NOT NULL DEFAULT 0,
                            distance_cm BIGINT NOT NULL DEFAULT 0,
                            playtime_seconds BIGINT NOT NULL DEFAULT 0
                        )
                        """
                ),
                // v2 – profese botů
                List.of(
                        "ALTER TABLE ba_bots ADD COLUMN role TEXT"
                ),
                // v3 – vesnice botů (id přiděluje SettlementService, ne DB)
                List.of(
                        """
                        CREATE TABLE IF NOT EXISTS ba_settlements (
                            id BIGINT PRIMARY KEY,
                            name TEXT NOT NULL,
                            world TEXT NOT NULL,
                            x INTEGER NOT NULL,
                            y INTEGER NOT NULL,
                            z INTEGER NOT NULL,
                            founder TEXT,
                            created_at BIGINT NOT NULL
                        )
                        """,
                        """
                        CREATE TABLE IF NOT EXISTS ba_settlement_members (
                            bot_id TEXT PRIMARY KEY,
                            settlement_id BIGINT NOT NULL,
                            joined_at BIGINT NOT NULL,
                            plot_index INTEGER,
                            plot_x INTEGER,
                            plot_y INTEGER,
                            plot_z INTEGER,
                            plot_facing TEXT
                        )
                        """,
                        "CREATE INDEX IF NOT EXISTS idx_ba_settlement_members_sid "
                                + "ON ba_settlement_members(settlement_id)"
                )
        );
    }
}
