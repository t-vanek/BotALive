package dev.botalive.core.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Načítá {@code config.yml} do typované {@link BotAliveConfig}.
 *
 * <p>Jediné místo v kódu, které zná YAML cesty a výchozí hodnoty. Chybějící
 * klíče se doplní defaulty, takže upgrade pluginu nerozbije starší konfiguraci.</p>
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    /**
     * Načte konfiguraci pluginu (a založí výchozí config.yml, pokud neexistuje).
     *
     * @param plugin instance pluginu
     * @return typovaná konfigurace
     */
    public static BotAliveConfig load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        var network = new BotAliveConfig.Network(
                c.getString("network.host", "127.0.0.1"),
                c.getInt("network.port", 0),
                c.getInt("network.connect-timeout-ms", 10_000),
                new BotAliveConfig.Reconnect(
                        c.getBoolean("network.reconnect.enabled", true),
                        c.getLong("network.reconnect.delay-min-ms", 3_000L),
                        c.getLong("network.reconnect.delay-max-ms", 15_000L),
                        c.getInt("network.reconnect.max-attempts", 10)
                )
        );

        var bots = new BotAliveConfig.Bots(
                c.getInt("bots.max-count", 50),
                c.getBoolean("bots.auto-spawn.enabled", false),
                c.getInt("bots.auto-spawn.count", 3),
                c.getInt("bots.auto-spawn.delay-seconds", 10),
                c.getStringList("bots.name-pool")
        );

        var ai = new BotAliveConfig.Ai(
                Math.max(1, c.getInt("ai.decision-interval-ticks", 5)),
                c.getDouble("ai.goal-hysteresis", 1.15),
                c.getInt("ai.view-distance-blocks", 32),
                c.getString("ai.difficulty", "normal")
        );

        var chat = new BotAliveConfig.Chat(
                c.getBoolean("chat.enabled", true),
                c.getDouble("chat.reply-chance", 0.75),
                c.getInt("chat.words-per-minute", 160),
                c.getInt("chat.max-queued-replies", 2)
        );

        var combat = new BotAliveConfig.Combat(
                c.getBoolean("combat.enabled", true),
                c.getInt("combat.reaction-min-ms", 150),
                c.getInt("combat.reaction-max-ms", 450),
                c.getBoolean("combat.strafing", true),
                c.getBoolean("combat.shield-use", true)
        );

        var economy = new BotAliveConfig.Economy(
                c.getBoolean("economy.enabled", true),
                c.getDouble("economy.starting-balance", 100.0)
        );

        var worlds = new BotAliveConfig.Worlds(
                List.copyOf(c.getStringList("worlds.whitelist")),
                List.copyOf(c.getStringList("worlds.blacklist"))
        );

        var spawn = new BotAliveConfig.Spawn(
                c.getString("spawn.mode", "world-spawn"),
                c.getString("spawn.world", ""),
                c.getDouble("spawn.x", 0),
                c.getDouble("spawn.y", 64),
                c.getDouble("spawn.z", 0),
                c.getInt("spawn.radius", 64)
        );

        var teleport = new BotAliveConfig.Teleport(
                c.getBoolean("teleport.enabled", true),
                c.getInt("teleport.player-cooldown-seconds", 30)
        );

        var performance = new BotAliveConfig.Performance(
                c.getInt("performance.tick-threads", 0),
                c.getInt("performance.pathfinding-threads", 0),
                c.getInt("performance.chunk-cache.size", 4096),
                c.getLong("performance.chunk-cache.ttl-ms", 3_000L),
                Math.max(1, c.getInt("performance.server-snapshot-ticks", 10))
        );

        var persistence = new BotAliveConfig.Persistence(
                c.getString("persistence.type", "sqlite").toLowerCase(),
                c.getString("persistence.sqlite.file", "botalive.db"),
                c.getString("persistence.postgresql.host", "localhost"),
                c.getInt("persistence.postgresql.port", 5432),
                c.getString("persistence.postgresql.database", "botalive"),
                c.getString("persistence.postgresql.user", "botalive"),
                c.getString("persistence.postgresql.password", ""),
                c.getInt("persistence.postgresql.pool-size", 8),
                c.getInt("persistence.flush-seconds", 15)
        );

        return new BotAliveConfig(network, bots, ai, chat, combat, economy,
                worlds, spawn, teleport, performance, persistence);
    }
}
