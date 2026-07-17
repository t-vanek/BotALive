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
 * @param pvp         PvP botů a aliance
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
        Pvp pvp,
        Performance performance,
        Persistence persistence
) {

    /**
     * Síťové nastavení – kam se boti připojují.
     *
     * @param host             adresa serveru (typicky 127.0.0.1)
     * @param port             port serveru; 0 = převzít z běžícího serveru
     * @param connectTimeoutMs timeout TCP připojení
     * @param worldModel       zdroj geometrie světa: {@code server} (chunk
     *                         snapshoty hostitelského serveru – výchozí) nebo
     *                         {@code packet} (parsování chunk paketů – nutné,
     *                         hrají-li boti na cizím serveru)
     * @param versionCheck     před připojením ověřit shodu verzí protokolu
     *                         (příp. přítomnost ViaVersion/ViaBackwards) a při
     *                         neshodě vytvoření bota odmítnout s vysvětlením;
     *                         {@code false} = jen varovat a nechat login selhat
     * @param reconnect        automatické znovupřipojení po výpadku
     */
    public record Network(String host, int port, int connectTimeoutMs,
                          String worldModel, boolean versionCheck, Reconnect reconnect) {

        /** @return {@code true} pokud se má použít klientský (paketový) world model */
        public boolean packetWorldModel() {
            return "packet".equalsIgnoreCase(worldModel);
        }

        /**
         * Míří boti na tento (hostitelský) server?
         *
         * @param serverPort port běžícího serveru
         * @return {@code true} pro loopback adresu a shodný (či zděděný) port
         */
        public boolean targetsLocalServer(int serverPort) {
            boolean loopback = host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost")
                    || host.equals("::1");
            return loopback && (port == 0 || port == serverPort);
        }
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
     * @param nameStyle        styl generovaných jmen: mixed | real | gamer
     *                         (uplatní se jen bez vlastního {@code namePool})
     * @param randomRoles      zda novým botům přidělovat profese podle osobnosti
     */
    public record Bots(int maxCount, boolean autoSpawnEnabled, int autoSpawnCount,
                       int autoSpawnDelayS, List<String> namePool, String nameStyle,
                       boolean randomRoles) {
    }

    /**
     * Nastavení AI.
     *
     * @param decisionIntervalTicks jak často mozek přehodnocuje cíle (v ticích)
     * @param goalHysteresis        bonus aktivního cíle proti kmitání (1.15 = +15 %)
     * @param viewDistanceBlocks    dohled botů na entity (bloky)
     * @param difficulty            easy|normal|hard|nightmare – škáluje reakce a přesnost
     * @param terraforming          smí boti měnit terén (razit štoly za rudou, upravovat
     *                              staveniště domů); false = jen povrchová těžba
     * @param dailyRhythm           denní rytmus: ráno pole, přes den těžba/stavba,
     *                              večer družení, v noci domů (jemné násobiče utility)
     * @param desperation           nouzové chování: hladový bot bez prostředků krade
     *                              z truhel a v krajní nouzi přepadá (jen v mezích pvp.*)
     */
    public record Ai(int decisionIntervalTicks, double goalHysteresis,
                     int viewDistanceBlocks, String difficulty, boolean terraforming,
                     boolean dailyRhythm, boolean desperation) {
    }

    /**
     * Chat botů.
     *
     * @param enabled          zda boti smí mluvit
     * @param language         jazyk frází – kód souboru {@code lang/<kód>.yml}
     *                         (vestavěné: cs, en; vlastní překlad = nový soubor)
     * @param replyChance      základní šance odpovědět na zmínku (násobí se povahou)
     * @param wordsPerMinute   průměrná rychlost psaní (slova/min)
     * @param maxQueuedReplies strop rozepsaných odpovědí (ochrana proti spamu)
     */
    public record Chat(boolean enabled, String language, double replyChance,
                       int wordsPerMinute, int maxQueuedReplies) {
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
     * Ekonomika botů.
     *
     * @param enabled         zapnuto/vypnuto
     * @param startingBalance počáteční zůstatek nového bota
     * @param vault           použít serverovou ekonomiku přes Vault, je-li dostupná
     *                        (jinak interní peněženka v databázi)
     */
    public record Economy(boolean enabled, double startingBalance, boolean vault) {
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
     * PvP botů a aliance.
     *
     * @param enabled              hlavní vypínač – vypnuto = boti nikdy nenapadají
     *                             hráče ani jiné boty (PvE zůstává)
     * @param attackPlayers        smí boti sami <b>iniciovat</b> útok na skutečné
     *                             hráče? (obrana po napadení funguje vždy,
     *                             je-li PvP zapnuté)
     * @param attackBots           smí boti napadat jiné boty
     * @param helpAllies           spojenci (přátelství z paměti) si chodí na pomoc
     * @param helpRadius           dosah volání o pomoc (bloky)
     * @param maxAttackersPerTarget férovost: kolik botů smí současně útočit
     *                             na jeden cíl
     */
    public record Pvp(boolean enabled, boolean attackPlayers, boolean attackBots,
                      boolean helpAllies, int helpRadius, int maxAttackersPerTarget) {
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
