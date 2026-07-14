package dev.botalive.core.config;

import java.util.List;

/**
 * Typovaná, nemutabilní konfigurace pluginu načtená z {@code config.yml}.
 *
 * <p>Records místo přímého čtení {@code FileConfiguration} po celém kódu:
 * překlepy v cestách se projeví na jednom místě (v {@link ConfigLoader}),
 * zbytek kódu pracuje s typy a výchozími hodnotami.</p>
 *
 * @param network     síťové nastavení klientů botů
 * @param bots        limity a automatický spawn botů
 * @param ai          chování AI (frekvence rozhodování, obtížnost)
 * @param chat        nastavení chatu botů
 * @param combat      nastavení boje
 * @param economy     vnitřní ekonomika botů
 * @param worlds      whitelist/blacklist světů
 * @param spawn       kde se boti spawnují
 * @param teleport    teleportace hráčů k botům a botů k hráčům
 * @param performance výkonnostní laditelné parametry
 * @param persistence databáze
 */
public record BotAliveConfig(
        Network network,
        Bots bots,
        Ai ai,
        Chat chat,
        Combat combat,
        Economy economy,
        Worlds worlds,
        Spawn spawn,
        Teleport teleport,
        Performance performance,
        Persistence persistence
) {

    /**
     * Síťové nastavení – kam se boti připojují.
     *
     * @param host             adresa serveru (typicky 127.0.0.1)
     * @param port             port serveru; 0 = převzít z běžícího serveru
     * @param connectTimeoutMs timeout TCP připojení
     * @param reconnect        automatické znovupřipojení po výpadku
     */
    public record Network(String host, int port, int connectTimeoutMs, Reconnect reconnect) {
    }

    /**
     * Automatický reconnect botů.
     *
     * @param enabled     zapnuto/vypnuto
     * @param delayMinMs  minimální zpoždění pokusu
     * @param delayMaxMs  maximální zpoždění pokusu (náhodně mezi min a max)
     * @param maxAttempts maximum pokusů v řadě, než to bot vzdá
     */
    public record Reconnect(boolean enabled, long delayMinMs, long delayMaxMs, int maxAttempts) {
    }

    /**
     * Limity botů a automatický spawn při startu serveru.
     *
     * @param maxCount         tvrdý strop počtu botů
     * @param autoSpawnEnabled zda po startu serveru automaticky spawnovat boty
     * @param autoSpawnCount   kolik botů spawnovat
     * @param autoSpawnDelayS  zpoždění po startu serveru (s), než se začne spawnovat
     * @param namePool         jména používaná generátorem identit
     */
    public record Bots(int maxCount, boolean autoSpawnEnabled, int autoSpawnCount,
                       int autoSpawnDelayS, List<String> namePool) {
    }

    /**
     * Nastavení AI.
     *
     * @param decisionIntervalTicks jak často mozek přehodnocuje cíle (v ticích)
     * @param goalHysteresis        bonus aktivního cíle proti kmitání (1.15 = +15 %)
     * @param viewDistanceBlocks    dohled botů na entity (bloky)
     * @param difficulty            easy|normal|hard|nightmare – škáluje reakce a přesnost
     */
    public record Ai(int decisionIntervalTicks, double goalHysteresis,
                     int viewDistanceBlocks, String difficulty) {
    }

    /**
     * Chat botů.
     *
     * @param enabled          zda boti smí mluvit
     * @param replyChance      základní šance odpovědět na zmínku (násobí se povahou)
     * @param wordsPerMinute   průměrná rychlost psaní (slova/min)
     * @param maxQueuedReplies strop rozepsaných odpovědí (ochrana proti spamu)
     */
    public record Chat(boolean enabled, double replyChance, int wordsPerMinute, int maxQueuedReplies) {
    }

    /**
     * Boj.
     *
     * @param enabled       zda boti smí bojovat
     * @param reactionMinMs nejrychlejší reakce na útok
     * @param reactionMaxMs nejpomalejší reakce na útok
     * @param strafing      zda boti při boji strafují
     * @param shieldUse     zda boti používají štít
     */
    public record Combat(boolean enabled, int reactionMinMs, int reactionMaxMs,
                         boolean strafing, boolean shieldUse) {
    }

    /**
     * Vnitřní ekonomika.
     *
     * @param enabled         zapnuto/vypnuto
     * @param startingBalance počáteční zůstatek nového bota
     */
    public record Economy(boolean enabled, double startingBalance) {
    }

    /**
     * Omezení světů.
     *
     * @param whitelist povolené světy (prázdné = všechny)
     * @param blacklist zakázané světy
     */
    public record Worlds(List<String> whitelist, List<String> blacklist) {

        /** @return {@code true} pokud smí boti do daného světa */
        public boolean allowed(String worldName) {
            if (blacklist.contains(worldName)) {
                return false;
            }
            return whitelist.isEmpty() || whitelist.contains(worldName);
        }
    }

    /**
     * Spawn botů.
     *
     * @param mode   {@code world-spawn} | {@code fixed} | {@code random-around}
     * @param world  cílový svět (prázdné = hlavní svět)
     * @param x      pevná souřadnice X (mode=fixed / střed pro random-around)
     * @param y      pevná souřadnice Y
     * @param z      pevná souřadnice Z
     * @param radius poloměr pro random-around
     */
    public record Spawn(String mode, String world, double x, double y, double z, int radius) {
    }

    /**
     * Teleportace hráč ↔ bot.
     *
     * @param enabled                zda je teleportace pro běžné hráče zapnutá
     *                               (admin s {@code botalive.admin} funguje vždy)
     * @param playerCooldownSeconds  cooldown mezi teleporty jednoho hráče (0 = bez limitu)
     */
    public record Teleport(boolean enabled, int playerCooldownSeconds) {
    }

    /**
     * Výkonnostní parametry.
     *
     * @param tickThreads              vlákna tick enginu botů; 0 = auto podle CPU
     * @param pathfindingThreads       vlákna A* výpočtů; 0 = auto
     * @param chunkCacheSize           maximum chunk snapshotů v cache
     * @param chunkCacheTtlMs          životnost snapshotu (ms)
     * @param serverSnapshotTicks      perioda server-side snapshotu bota (ticky)
     */
    public record Performance(int tickThreads, int pathfindingThreads, int chunkCacheSize,
                              long chunkCacheTtlMs, int serverSnapshotTicks) {
    }

    /**
     * Databáze.
     *
     * @param type          {@code sqlite} | {@code postgresql}
     * @param sqliteFile    cesta k SQLite souboru (relativně k data folderu)
     * @param pgHost        PostgreSQL host
     * @param pgPort        PostgreSQL port
     * @param pgDatabase    název databáze
     * @param pgUser        uživatel
     * @param pgPassword    heslo
     * @param pgPoolSize    velikost connection poolu
     * @param flushSeconds  perioda write-behind flushe paměti botů
     */
    public record Persistence(String type, String sqliteFile,
                              String pgHost, int pgPort, String pgDatabase,
                              String pgUser, String pgPassword, int pgPoolSize,
                              int flushSeconds) {
    }
}
