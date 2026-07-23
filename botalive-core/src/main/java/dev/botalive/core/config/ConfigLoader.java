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
                c.getBoolean("network.version-check", true),
                new BotAliveConfig.Reconnect(
                        c.getBoolean("network.reconnect.enabled", true),
                        c.getLong("network.reconnect.delay-min-ms", 3_000L),
                        c.getLong("network.reconnect.delay-max-ms", 15_000L),
                        c.getInt("network.reconnect.max-attempts", 10)
                ),
                new BotAliveConfig.Network.Velocity(
                        c.getBoolean("network.velocity.enabled", false),
                        c.getString("network.velocity.secret", "")
                )
        );

        var gateway = new BotAliveConfig.Gateway(
                c.getBoolean("gateway.enabled", true),
                c.getBoolean("gateway.enforce-prelogin", true),
                c.getBoolean("gateway.restrict-source", true),
                c.getBoolean("gateway.http.enabled", false),
                c.getString("gateway.http.bind", "127.0.0.1"),
                c.getInt("gateway.http.port", 41000),
                Math.max(5, c.getInt("gateway.token-ttl-seconds", 120)),
                c.getBoolean("gateway.client-auth", false),
                c.getString("gateway.secret", ""),
                c.getBoolean("gateway.mojang-proxy", false),
                c.getString("gateway.mojang-host", "https://sessionserver.mojang.com")
        );

        var bots = new BotAliveConfig.Bots(
                c.getInt("bots.max-count", 50),
                c.getBoolean("bots.auto-spawn.enabled", false),
                c.getInt("bots.auto-spawn.count", 3),
                c.getInt("bots.auto-spawn.delay-seconds", 10),
                c.getStringList("bots.name-pool"),
                c.getString("bots.name-style", "mixed"),
                c.getBoolean("bots.random-roles", true),
                c.getBoolean("bots.starter-kit", true)
        );

        var ai = new BotAliveConfig.Ai(
                Math.max(1, c.getInt("ai.decision-interval-ticks", 5)),
                c.getDouble("ai.goal-hysteresis", 1.15),
                c.getInt("ai.view-distance-blocks", 32),
                c.getString("ai.difficulty", "normal"),
                c.getBoolean("ai.terraforming", true),
                c.getBoolean("ai.ladders", true),
                c.getBoolean("ai.pillaring", true),
                c.getBoolean("ai.boats", true),
                c.getBoolean("ai.daily-rhythm", true),
                c.getBoolean("ai.desperation", true),
                c.getBoolean("ai.mood", true),
                c.getBoolean("ai.vitals", true),
                c.getBoolean("ai.drives", true),
                c.getBoolean("ai.crawling", false)
        );

        var chat = new BotAliveConfig.Chat(
                c.getBoolean("chat.enabled", true),
                c.getString("chat.language", "cs"),
                c.getDouble("chat.reply-chance", 0.75),
                c.getInt("chat.words-per-minute", 160),
                c.getInt("chat.max-queued-replies", 2)
        );

        var combat = new BotAliveConfig.Combat(
                c.getBoolean("combat.enabled", true),
                c.getInt("combat.reaction-min-ms", 150),
                c.getInt("combat.reaction-max-ms", 450),
                c.getBoolean("combat.strafing", true),
                c.getBoolean("combat.shield-use", true),
                c.getBoolean("combat.splash-potions", true)
        );

        var employment = new BotAliveConfig.Employment(
                c.getBoolean("economy.employment.enabled", true),
                c.getBoolean("economy.employment.require-payment", true),
                Math.max(1, c.getInt("economy.employment.max-bots-per-player", 2)),
                Math.max(1, c.getInt("economy.employment.max-days", 7)),
                Math.max(0, c.getDouble("economy.employment.worker-wage-per-day", 12.0)),
                Math.max(0, c.getDouble("economy.employment.guard-wage-per-day", 18.0))
        );

        var economy = new BotAliveConfig.Economy(
                c.getBoolean("economy.enabled", true),
                c.getDouble("economy.starting-balance", 100.0),
                c.getBoolean("economy.vault", true),
                c.getBoolean("economy.bot-trade", true),
                c.getBoolean("economy.player-trade", true),
                employment
        );

        var memory = new BotAliveConfig.Memory(
                c.getBoolean("memory.relation-decay.enabled", true),
                Math.max(0, c.getDouble("memory.relation-decay.friend-per-day", 0.01)),
                Math.max(0, c.getDouble("memory.relation-decay.enemy-per-day", 0.03)),
                Math.min(1, Math.max(0, c.getDouble("memory.relation-decay.floor", 0.1)))
        );

        var worlds = new BotAliveConfig.Worlds(
                List.copyOf(c.getStringList("worlds.whitelist")),
                List.copyOf(c.getStringList("worlds.blacklist"))
        );

        var spawn = new BotAliveConfig.Spawn(
                c.getString("spawn.mode", "random-around"),
                c.getString("spawn.world", ""),
                c.getDouble("spawn.x", 0),
                c.getDouble("spawn.y", 64),
                c.getDouble("spawn.z", 0),
                c.getInt("spawn.radius", 320),
                c.getInt("spawn.min-radius", 64),
                c.getBoolean("spawn.around-world-spawn", true)
        );

        var teleport = new BotAliveConfig.Teleport(
                c.getBoolean("teleport.enabled", true),
                c.getInt("teleport.player-cooldown-seconds", 30)
        );

        var pvp = new BotAliveConfig.Pvp(
                c.getBoolean("pvp.enabled", false),
                c.getBoolean("pvp.attack-players", false),
                c.getBoolean("pvp.attack-bots", true),
                c.getBoolean("pvp.help-allies", true),
                c.getInt("pvp.help-radius", 24),
                Math.max(1, c.getInt("pvp.max-attackers-per-target", 2))
        );

        var war = new BotAliveConfig.War(
                c.getBoolean("settlement.war.enabled", false),
                Math.max(1.0, c.getDouble("settlement.war.declare-threshold", 6.0)),
                Math.max(0, c.getDouble("settlement.war.theft-weight", 1.5)),
                Math.max(0, c.getDouble("settlement.war.assault-weight", 0.75)),
                Math.max(0, c.getDouble("settlement.war.decay-per-hour", 0.5)),
                Math.max(2, c.getInt("settlement.war.min-members", 3)),
                Math.max(1, c.getInt("settlement.war.raid-size", 3)),
                Math.max(5, c.getInt("settlement.war.raid-cooldown-minutes", 20)),
                Math.max(1, c.getInt("settlement.war.weariness-deaths", 4)),
                Math.max(1, c.getInt("settlement.war.max-war-hours", 6)),
                Math.max(1, c.getInt("settlement.war.truce-hours", 12)),
                c.getBoolean("settlement.war.reparations", true),
                Math.max(0, c.getDouble("settlement.war.reparations-max", 40.0))
        );

        var settlement = new BotAliveConfig.Settlement(
                c.getBoolean("settlement.enabled", true),
                Math.max(8, c.getInt("settlement.plot-spacing", 12)),
                Math.max(2, c.getInt("settlement.max-members", 8)),
                Math.max(32, c.getInt("settlement.join-radius", 200)),
                Math.max(48, c.getInt("settlement.min-village-distance", 150)),
                c.getDouble("settlement.loner-sociability", 0.30),
                c.getDouble("settlement.grudge-threshold", 0.60),
                Math.max(1, c.getInt("settlement.change-cooldown-minutes", 30)),
                c.getBoolean("settlement.lighting", true),
                c.getBoolean("settlement.paths", true),
                c.getBoolean("settlement.walls", false),
                c.getBoolean("settlement.fences", false),
                Math.max(1, c.getInt("settlement.wall-height", 2)),
                Math.max(0, c.getInt("settlement.ghost-days", 7)),
                Math.max(1, c.getInt("settlement.grudge-window-hours", 2)),
                c.getBoolean("settlement.protect-structures", true),
                Math.max(0, c.getInt("settlement.protect-depth", 8)),
                Math.max(0, c.getInt("settlement.mine-distance", 24)),
                Math.max(0, c.getInt("settlement.spawn-keepout", 48)),
                war
        );

        var wither = new BotAliveConfig.Wither(
                c.getBoolean("nether.wither.enabled", false),
                Math.min(1, Math.max(0, c.getDouble("nether.wither.min-courage", 0.75))),
                Math.max(3, c.getInt("nether.wither.max-fight-minutes", 12))
        );

        var nether = new BotAliveConfig.Nether(
                c.getBoolean("nether.enabled", true),
                c.getBoolean("nether.build-portals", true),
                c.getBoolean("nether.barter", true),
                Math.max(3, c.getInt("nether.max-trip-minutes", 20)),
                Math.min(5, Math.max(3, c.getInt("nether.min-gear-tier", 4))),
                c.getBoolean("nether.striders", true),
                c.getBoolean("nether.brewing", true),
                c.getBoolean("nether.respawn-anchor", true),
                Math.min(64, Math.max(4, c.getInt("nether.lava-bridge-limit", 12))),
                wither
        );

        var endOuter = new BotAliveConfig.Outer(
                c.getBoolean("end.outer.enabled", true),
                Math.max(5, c.getInt("end.outer.max-trip-minutes", 25)),
                c.getBoolean("end.outer.locate-assist", true),
                Math.max(1, c.getInt("end.outer.pearl-reserve", 2)),
                Math.max(64, c.getInt("end.outer.max-city-distance", 400)),
                c.getBoolean("end.outer.elytra", true),
                c.getBoolean("end.outer.rockets", true),
                c.getBoolean("end.outer.void-bridge", true),
                c.getBoolean("end.outer.shulker-boxes", true)
        );

        var end = new BotAliveConfig.End(
                c.getBoolean("end.enabled", true),
                c.getBoolean("end.dragon-fight", true),
                c.getBoolean("end.hunt-endermen", true),
                Math.max(1, c.getInt("end.expedition-cooldown-minutes", 90)),
                Math.min(1, Math.max(0, c.getDouble("end.min-courage", 0.5))),
                Math.max(3, c.getInt("end.max-fight-minutes", 15)),
                endOuter
        );

        var pathfinding = new BotAliveConfig.Pathfinding(
                Math.max(500, c.getInt("pathfinding.node-budget", 8_000)),
                Math.max(0, c.getInt("pathfinding.time-budget-ms", 25)),
                c.getBoolean("pathfinding.far-corridor", true),
                c.getBoolean("pathfinding.planned-actions", true)
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

        int buildWidth = Math.max(5, c.getInt("build.width", 9));
        if (buildWidth % 2 == 0) {
            buildWidth++; // lichý půdorys – čistý střed pro stavitele
        }
        // Strop půdorysu musí zůstat pod rozestupem parcel (jinak by dům lezl
        // k sousedovi a vyčníval z ochranného pásma). Zkrátit na plot-spacing − 3
        // (lichý), aby mezi domy zbyla cesta i při zvednutém stropu velikosti.
        int plotSpacing = settlement.plotSpacing();
        int widthCeiling = plotSpacing - 3;
        if (widthCeiling % 2 == 0) {
            widthCeiling--;
        }
        buildWidth = Math.max(5, Math.min(buildWidth, widthCeiling));
        int buildWallHeight = Math.max(2, c.getInt("build.wall-height", 3));
        var build = new BotAliveConfig.Build(
                c.getBoolean("build.complex", true),
                buildWidth,
                buildWallHeight,
                Math.max(buildWallHeight, c.getInt("build.max-wall-height", 5)),
                c.getBoolean("build.grow", false),
                new BotAliveConfig.Build.Site(
                        Math.max(4, c.getInt("build.site.surface-scan", 24)),
                        Math.max(0, c.getInt("build.site.max-fills", 4)),
                        Math.max(0, c.getInt("build.site.max-digs", 8)),
                        Math.max(1, c.getInt("build.site.fill-divisor", 4)),
                        Math.max(1, c.getInt("build.site.dig-divisor", 2)),
                        Math.max(0, c.getInt("build.site.search-radius", 4)))
        );

        return new BotAliveConfig(network, gateway, bots, ai, chat, combat, economy, memory,
                worlds, spawn, teleport, pvp, settlement, nether, end, pathfinding,
                performance, persistence, build);
    }
}
