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
 * @param gateway     vlastní Mojang API gateway a ověřování botů (proti zneužití)
 * @param bots        limity a automatický spawn botů
 * @param ai          chování AI (frekvence rozhodování, obtížnost)
 * @param chat        nastavení chatu botů
 * @param combat      nastavení boje
 * @param economy     vnitřní ekonomika botů
 * @param memory      paměť botů (rozpad vztahů)
 * @param worlds      whitelist/blacklist světů
 * @param spawn       kde se boti spawnují
 * @param teleport    teleportace hráčů k botům a botů k hráčům
 * @param pvp         PvP botů a aliance
 * @param settlement  vesnice botů (společné stavění, parcely, roztržky)
 * @param nether      výpravy do Netheru (portály, těžba, netherit)
 * @param end         výpravy do dimenze End (drak, perly, návrat)
 * @param pathfinding rozpočty výpočtu cest
 * @param performance výkonnostní laditelné parametry
 * @param persistence databáze
 * @param build       rozmanité stavby (engine v2 – větší domy z palet)
 */
public record BotAliveConfig(
        Network network,
        Gateway gateway,
        Bots bots,
        Ai ai,
        Chat chat,
        Combat combat,
        Economy economy,
        Memory memory,
        Worlds worlds,
        Spawn spawn,
        Teleport teleport,
        Pvp pvp,
        Settlement settlement,
        Nether nether,
        End end,
        Pathfinding pathfinding,
        Performance performance,
        Persistence persistence,
        Build build
) {

    /**
     * Rozmanité stavby (stavební engine v2). Vypnuto = dnešní domek 4×4
     * (plná zpětná kompatibilita); zapnuto = generované domy z palet podle
     * místního dřeva (větší půdorys, okna, valbová střecha).
     *
     * @param complex    zapnout generované domy místo legacy 4×4
     * @param width      strop půdorysu (lichý, ≥ 5); skutečnou velikost volí
     *                   bot podle stupně sídla a povahy
     * @param wallHeight výška zdí
     */
    public record Build(boolean complex, int width, int wallHeight) {
    }

    /**
     * Síťové nastavení – kam se boti připojují.
     *
     * @param host             adresa serveru (typicky 127.0.0.1)
     * @param port             port serveru; 0 = převzít z běžícího serveru
     * @param connectTimeoutMs timeout TCP připojení
     * @param versionCheck     před připojením ověřit shodu verzí protokolu
     *                         (příp. přítomnost ViaVersion/ViaBackwards) a při
     *                         neshodě vytvoření bota odmítnout s vysvětlením;
     *                         {@code false} = jen varovat a nechat login selhat
     * @param reconnect        automatické znovupřipojení po výpadku
     * @param velocity         Velocity modern forwarding (backend za proxy)
     */
    public record Network(String host, int port, int connectTimeoutMs,
                          boolean versionCheck, Reconnect reconnect, Velocity velocity) {

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

        /**
         * Velocity „modern" player-info forwarding na straně bota.
         *
         * <p>Pro nasazení, kde boti míří na offline-mode backend za Velocity
         * proxy: backend při loginu vyžaduje podepsaný identity payload. Bot ho
         * podepíše stejným tajným klíčem jako proxy ({@code forwarding.secret}).</p>
         *
         * @param enabled zapnout odpovídání na Velocity player-info dotaz
         * @param secret  sdílený tajný klíč (shodný s {@code forwarding.secret} Velocity)
         */
        public record Velocity(boolean enabled, String secret) {

            /** @return tajný klíč jako UTF-8 bajty (prázdné pole pro prázdný klíč) */
            public byte[] secretBytes() {
                return secret == null ? new byte[0]
                        : secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
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
     * Vlastní Mojang API gateway a ověřovací proces botů (ochrana proti zneužití).
     *
     * <p>Na offline-mode serveru se kdokoli může připojit pod libovolným jménem –
     * včetně jmen botů. Gateway to řeší: BotAlive vydává každému botu krátkodobé
     * podepsané pověření a server-side pojistka odmítne přihlášení pod jménem
     * bota bez platného ověření.</p>
     *
     * @param enabled         hlavní vypínač – vydávat pověření a provozovat autoritu
     * @param enforcePreLogin odmítat přihlášení pod jménem bota bez platné autorizace
     *                        (server-side pojistka {@code BotLoginGuard})
     * @param restrictSource  navíc vyžadovat, aby přihlášení pod jménem bota přišlo
     *                        z lokálního zdroje (loopback/LAN) – veřejné adresy jsou
     *                        typický vektor zneužití
     * @param httpEnabled     spustit vestavěnou HTTP gateway (tvar Mojang session API);
     *                        potřeba jen pro online-mode nasazení nebo proxy/fleet
     * @param bind            adresa, na které HTTP gateway naslouchá (typicky 127.0.0.1)
     * @param port            port HTTP gateway (0 = přidělí OS)
     * @param tokenTtlSeconds platnost vydaného pověření v sekundách
     * @param clientAuth      boti se ověřují online-mode přes gateway (vyžaduje
     *                        online-mode server nasměrovaný na gateway) – pokročilé
     * @param secret          tajný klíč HMAC; prázdný = vygenerovat a uložit do
     *                        {@code gateway-secret.key} (sdílený klíč pro fleet)
     * @param mojangProxy     přeposílat dotazy pro neznámá jména/UUID (reální hráči)
     *                        na skutečný Mojang – online-mode server pak ověří zároveň
     *                        boty (lokálně) i hráče (přes Mojang); vyžaduje {@code httpEnabled}
     * @param mojangHost      základní URL skutečného session serveru pro proxy
     *                        (prázdné = {@code https://sessionserver.mojang.com})
     */
    public record Gateway(boolean enabled, boolean enforcePreLogin, boolean restrictSource,
                          boolean httpEnabled, String bind, int port, int tokenTtlSeconds,
                          boolean clientAuth, String secret,
                          boolean mojangProxy, String mojangHost) {

        /** @return platnost pověření v ms (spodní strop 5 s) */
        public long tokenTtlMs() {
            return Math.max(5, tokenTtlSeconds) * 1000L;
        }

        /**
         * @return základní URL, na které se klient (bot) dovolá gateway; wildcard
         *         bind ({@code 0.0.0.0}/{@code ::}) se překládá na loopback
         */
        public String clientBaseUrl() {
            String h = bind == null || bind.isBlank() || bind.equals("0.0.0.0") || bind.equals("::")
                    ? "127.0.0.1" : bind;
            return "http://" + h + ":" + port;
        }
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
     * @param ladders               smí boti stavět žebříky na přelezení svislých stěn
     *                              vyšších než skok (vyžaduje {@code terraforming})
     * @param pillaring             smí se boti pilířovat vzhůru (skok + blok pod sebe),
     *                              když cíl leží výš a není stěna na žebřík
     *                              (vyžaduje {@code terraforming})
     * @param boats                 smí boti použít loď k překonání široké vody: když
     *                              navigace míří přes souvislou vodní plochu a bot má
     *                              loď (v inventáři nebo poblíž), nasedne a přepluje ji
     *                              místo pomalého plavání
     * @param dailyRhythm           denní rytmus: ráno pole, přes den těžba/stavba,
     *                              večer družení, v noci domů (jemné násobiče utility)
     * @param desperation           nouzové chování: hladový bot bez prostředků krade
     *                              z truhel a v krajní nouzi přepadá (jen v mezích pvp.*)
     */
    public record Ai(int decisionIntervalTicks, double goalHysteresis,
                     int viewDistanceBlocks, String difficulty, boolean terraforming,
                     boolean ladders, boolean pillaring, boolean boats, boolean dailyRhythm,
                     boolean desperation) {
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
     * @param splashPotions ofenzivní házení splash lektvarů (zranění/jed) na
     *                      cíl boje – na nemrtvé se nehází nikdy (zranění je
     *                      léčí, jed na ně nefunguje); lektvary jsou z vaření,
     *                      barteru a kořisti
     */
    public record Combat(boolean enabled, int reactionMinMs, int reactionMaxMs,
                         boolean strafing, boolean shieldUse, boolean splashPotions) {
    }

    /**
     * Ekonomika botů.
     *
     * @param enabled         zapnuto/vypnuto
     * @param startingBalance počáteční zůstatek nového bota
     * @param vault           použít serverovou ekonomiku přes Vault, je-li dostupná
     *                        (jinak interní peněženka v databázi)
     * @param botTrade        trh mezi boty: přebytky se nabízejí okolí a boti
     *                        si je navzájem kupují (peníze obíhají uvnitř
     *                        společenství, profese dostávají ekonomický smysl)
     * @param playerTrade     prodej hráčům: hráč si vyvolávanou nabídku vezme
     *                        („beru!"), zaplatí přes {@code /pay} a bot mu
     *                        předá zboží; funguje jen s Vault ekonomikou
     *                        (bez ní nelze ověřit příchozí platbu)
     * @param employment      najímání botů hráči (dělník, bodyguard)
     */
    public record Economy(boolean enabled, double startingBalance, boolean vault,
                          boolean botTrade, boolean playerTrade,
                          Employment employment) {
    }

    /**
     * Najímání botů hráči ({@code /botalive hire}).
     *
     * <p>Hráč si najme bota jako <b>dělníka</b> (bot se soustředí na
     * produktivní práci a výtěžek pravidelně nosí zaměstnavateli) nebo
     * <b>bodyguarda</b> (chodí s hráčem a brání ho před moby; proti hráčům
     * a botům jen v mezích sekce {@code pvp}). Mzdu si bot řekne podle
     * povahy – lakomí a líní jsou dražší, kamarádi dávají slevu – a platí
     * se předem přes {@code /pay} (vyžaduje Vault). Zaměstnavatel, který
     * svého bota napadne, o něj přijde bez náhrady.</p>
     *
     * @param enabled          hlavní vypínač najímání
     * @param requirePayment   vyžadovat platbu předem přes Vault {@code /pay};
     *                         vypnuto = boti pracují „za kamarádství"
     *                         (servery bez ekonomiky)
     * @param maxBotsPerPlayer kolik botů smí mít jeden hráč najato současně
     * @param maxDays          strop délky smlouvy ve dnech
     * @param workerWagePerDay základní denní mzda dělníka (před povahou/slevou)
     * @param guardWagePerDay  základní denní mzda bodyguarda
     */
    public record Employment(boolean enabled, boolean requirePayment,
                             int maxBotsPerPlayer, int maxDays,
                             double workerWagePerDay, double guardWagePerDay) {
    }

    /**
     * Paměť botů.
     *
     * @param relationDecayEnabled časový rozpad vztahů (FRIEND/ENEMY): bez
     *                             oživování vztahy slábnou – bez rozpadu by
     *                             po týdnech provozu byl každý kamarádem
     *                             každého a stará zášť blokovala navěky
     * @param friendDecayPerDay    o kolik denně klesá neoživované přátelství
     * @param enemyDecayPerDay     o kolik denně klesá neoživovaná zášť
     *                             (čas rány hojí – rychleji než přátelství)
     * @param relationFloor        podlaha rozpadu – vztah pod ni neklesne
     *                             (vzpomínka zůstává, ale nic neovlivňuje)
     */
    public record Memory(boolean relationDecayEnabled, double friendDecayPerDay,
                         double enemyDecayPerDay, double relationFloor) {
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
     * Vesnice botů – domy se nestaví náhodně po lese, ale na parcelách
     * kolem návsi; členství vyrůstá z přátelství a roztržky z něj ubírají.
     *
     * @param enabled               hlavní vypínač (vypnuto = každý staví sám
     *                              jako dřív)
     * @param plotSpacing           rozestup parcel v mřížce vesnice (bloky)
     * @param maxMembers            kapacita vesnice (počet členů)
     * @param joinRadius            jak daleko bot hledá vesnici, ke které se
     *                              přidá (bloky)
     * @param minVillageDistance    minimální vzdálenost mezi vesnicemi –
     *                              odštěpenci zakládají až za ní (bloky)
     * @param lonerSociability      pod tímto rysem SOCIABILITY bot vesnice
     *                              ignoruje a staví sám (samotář)
     * @param grudgeThreshold       důležitost ENEMY vzpomínky na souseda,
     *                              která se počítá jako vážná zášť
     * @param changeCooldownMinutes minimální rozestup stěhování jednoho bota
     *                              (roztržka smí dřív)
     * @param lighting              po dostavění domu osvětlit pochodněmi linii
     *                              ke návsi (méně mobů ve vesnici v noci)
     * @param paths                 udusat lopatou cestičku od dveří k návsi
     * @param ghostDays             po kolika dnech bez připojení se člen
     *                              vesnice při startu serveru vyřadí
     *                              (0 = nikdy; dům mu ve vesnici zůstává)
     * @param grudgeWindowHours     jak čerstvá (hodiny) musí být zášť, aby
     *                              kvůli ní bot opustil vesnici – druhá
     *                              polovina knobu {@code grudgeThreshold}
     * @param war                   války a diplomacie mezi vesnicemi
     */
    public record Settlement(boolean enabled, int plotSpacing, int maxMembers,
                             int joinRadius, int minVillageDistance,
                             double lonerSociability, double grudgeThreshold,
                             int changeCooldownMinutes, boolean lighting,
                             boolean paths, int ghostDays, int grudgeWindowHours,
                             War war) {
    }

    /**
     * Války a diplomacie mezi vesnicemi botů.
     *
     * <p>Křivdy mezi členy různých vesnic (odhalené krádeže, napadení) zvedají
     * napětí mezi vesnicemi. Když napětí přeteče práh a starosta má dost
     * bojovné povahy, vyhlásí válku: vesnice posílají nájezdy, obránce svolává
     * stávající PvP mašinerie a padlí zvyšují únavu z války, dokud starostové
     * nedojednají příměří. Vše je čistě mezi boty – hráčů se války netýkají –
     * a nájezdy respektují sekci {@code pvp} (bez {@code pvp.enabled}
     * a {@code pvp.attack-bots} se válčí jen studeně, beze zbraní).</p>
     *
     * @param enabled              hlavní vypínač válek (vypnuto = napětí se
     *                             neměří a války se nevyhlašují)
     * @param declareThreshold     napětí, při kterém starosta zvažuje válku
     * @param theftWeight          napětí za odhalenou krádež mezi vesnicemi
     * @param assaultWeight        napětí za napadení člena cizí vesnice
     * @param decayPerHour         samovolný pokles napětí za hodinu klidu
     * @param minMembers           minimální počet členů obou vesnic – malé
     *                             osady války nevedou
     * @param raidSize             kolik nejbojovnějších členů vyráží na nájezd
     * @param raidCooldownMinutes  rozestup nájezdů jedné války (minuty)
     * @param wearinessDeaths      padlí na vlastní straně, po kterých starosta
     *                             žádá o příměří
     * @param maxWarHours          tvrdý strop délky války (hodiny) – pak se
     *                             uzavře příměří bez ohledu na ztráty
     * @param truceHours           délka příměří (hodiny); napětí mezitím klesá
     * @param reparations          poražená strana (více padlých) platí při
     *                             příměří reparace z peněženky starosty
     * @param reparationsMax       strop reparací (interní měna / Vault)
     */
    public record War(boolean enabled, double declareThreshold, double theftWeight,
                      double assaultWeight, double decayPerHour, int minMembers,
                      int raidSize, int raidCooldownMinutes, int wearinessDeaths,
                      int maxWarHours, int truceHours, boolean reparations,
                      double reparationsMax) {
    }

    /**
     * Výpravy do Netheru – bot s výbavou si postaví (nebo najde) portál,
     * v Netheru těží quartz, zlato, glowstone a starodávné trosky a vrací se
     * domů; z trosek pak taví netherit a u kovářského stolu povyšuje výbavu.
     *
     * @param enabled         hlavní vypínač – vypnuto = boti do Netheru sami
     *                        nechodí (portály ve světě dál obcházejí obloukem)
     * @param buildPortals    smí si boti stavět vlastní portály (14 obsidiánu
     *                        + křesadlo); vypnuto = používají jen nalezené
     * @param barter          výměnný obchod s pigliny – bot se zlatou zbrojí
     *                        jim hází zlaté ingoty a sbírá, co za ně padne
     * @param maxTripMinutes  časový rozpočet jedné výpravy (minuty); po
     *                        vyčerpání se bot vrací k portálu domů
     * @param minGearTier     minimální tier zbraně a zbroje pro výpravu
     *                        (4 = železo, 5 = diamant) – méně vybavení = smrt
     * @param striders        jízda na striderech: bot se sedlem a houbou na
     *                        prutu osedlá stridera a přejede lávový oceán,
     *                        přes který se nemostí
     * @param brewing         vaření lektvarů – kotlík progrese: netherová
     *                        bradavice (pevnosti + vlastní záhon na soul
     *                        sandu), varný stojan a lektvary odolnosti ohni,
     *                        síly, léčení a jedu (splash)
     * @param respawnAnchor   kotva respawnu: z crying obsidiánu a glowstonu
     *                        u netherového outpostu – smrt na výpravě vrací
     *                        bota k portálu, ne přes celý overworld
     * @param lavaBridgeLimit strop reaktivního mostu přes lávu (bloky);
     *                        delší lávové plochy se objíždějí nebo přejíždějí
     *                        na striderovi
     * @param wither          souboj s witherem (nether star) – default
     *                        vypnuto: exploze witheru ničí terén serveru
     */
    public record Nether(boolean enabled, boolean buildPortals, boolean barter,
                         int maxTripMinutes, int minGearTier, boolean striders,
                         boolean brewing, boolean respawnAnchor, int lavaBridgeLimit,
                         Wither wither) {
    }

    /**
     * Souboj s witherem – vrchol netherové progrese, default vypnutý.
     *
     * <p>Bot s dostatkem odvahy nasbírá v pevnosti 3 lebky wither skeletonů
     * a soul sand, daleko od portálu i outpostu postaví oltář, vyvolá withera
     * a bojuje: zdraví bosse čte z boss baru, do poloviny střílí lukem,
     * obrněnou druhou fázi dobíjí mečem. Nether star je trofej. Vypnuto
     * default – exploze witheru ničí terén (byť „jen" v Netheru), server ho
     * musí chtít.</p>
     *
     * @param enabled         hlavní vypínač souboje (sběr lebek se bez něj
     *                        neplánuje)
     * @param minCourage      minimální rys COURAGE – na withera jdou jen
     *                        ti nejodvážnější
     * @param maxFightMinutes rozpočet souboje (minuty); po vyčerpání bot
     *                        prchá a nechá withera witherem
     */
    public record Wither(boolean enabled, double minCourage, int maxFightMinutes) {
    }

    /**
     * Výpravy do dimenze End.
     *
     * <p>Bot, který zná portál do Endu (prošel jím, našel ho, doslechl se
     * o něm drby, nebo ho admin zadal přes {@code /botalive end portal}),
     * se s dostatečnou výbavou a odvahou vypraví za drakem. V Endu platí
     * tvrdá pravidla dimenze ({@code DimensionPolicy}): nespí se, nestaví,
     * na endermany se nekouká.</p>
     *
     * @param enabled                   hlavní vypínač výprav do Endu (chování
     *                                  v Endu – void, endermani – platí vždy,
     *                                  když se tam bot ocitne)
     * @param dragonFight               smí boti bojovat s drakem (vypnout na
     *                                  serverech, kde drak patří hráčům)
     * @param huntEndermen              smí odvážní boti v Endu cíleně lovit
     *                                  endermany kvůli perlám
     * @param expeditionCooldownMinutes minimální rozestup dvou výprav jednoho
     *                                  bota (minuty)
     * @param minCourage                minimální rys COURAGE pro výpravu –
     *                                  zbabělci do Endu nelezou
     * @param maxFightMinutes           rozpočet jednoho zátahu na draka –
     *                                  po vyčerpání si bot dá pauzu (jídlo,
     *                                  kořist, pokus o návrat) a pak to zkusí
     *                                  znovu; bez rozpočtu by bojoval do smrti
     * @param outer                     výpravy na vnější ostrovy (end cities,
     *                                  elytry)
     */
    public record End(boolean enabled, boolean dragonFight, boolean huntEndermen,
                      int expeditionCooldownMinutes, double minCourage,
                      int maxFightMinutes, Outer outer) {
    }

    /**
     * Výpravy na vnější ostrovy Endu (po skolení draka).
     *
     * <p>Bot s dostatkem perel prohodí perlu gatewayí (portál se objeví po
     * smrti draka na okraji hlavního ostrova), na vnějších ostrovech najde
     * end city – server-side asistencí {@code locateNearestStructure}
     * (precedent §9) nebo skenem purpuru při průzkumu – vybojuje si cestu
     * přes shulkery (levitaci klient simuluje), vyluští truhly, z lodi
     * sundá <b>elytry</b> (item frame) a vrátí se gatewayí domů. S elytrami
     * pak umí slétat z výšek klouzavým letem.</p>
     *
     * @param enabled         hlavní vypínač výprav na vnější ostrovy
     * @param maxTripMinutes  časový rozpočet výpravy za gatewayí (minuty)
     * @param locateAssist    server-side hledání end city
     *                        ({@code locateNearestStructure}); vypnuto nebo
     *                        v paketovém režimu se hledá skenem při průzkumu
     * @param pearlReserve    kolik perel si bot nechává na návrat
     * @param maxCityDistance nejdál, kam se za nalezeným městem půjde (bloky)
     * @param elytra          smí boti létat na elytrách (klouzavé slety)
     * @param rockets         rakety při letu na elytrách – boost prodlužuje
     *                        dolet, umožňuje start ze země a přelet voidu
     *                        k městům mimo klouzavý dosah
     * @param voidBridge      mostění přes void: město v dosahu
     *                        {@code maxCityDistance}, ke kterému nevede
     *                        ostrovní cesta, se dosáhne end stone lávkou
     *                        (rozpočet z inventáře) místo vzdání
     * @param shulkerBoxes    shulker boxy: craft z ulit, přenosná truhla na
     *                        výpravě (box se vykope i s obsahem) a druhá
     *                        truhla vedle domácí bedny
     */
    public record Outer(boolean enabled, int maxTripMinutes, boolean locateAssist,
                        int pearlReserve, int maxCityDistance, boolean elytra,
                        boolean rockets, boolean voidBridge, boolean shulkerBoxes) {
    }

    /**
     * Rozpočty výpočtu cest (A*).
     *
     * @param nodeBudget   maximum expandovaných uzlů jednoho výpočtu; při
     *                     vyčerpání se vrací nejlepší částečná cesta a bot
     *                     doplánuje cestou
     * @param timeBudgetMs časový strop jednoho výpočtu (ms); 0 = bez limitu –
     *                     garantovaná latence i v členitém terénu (voda,
     *                     jeskyně), kde jsou uzly drahé
     * @param farCorridor  hrubé koridory dálkových tras (A* nad povrchovými
     *                     sondami) – boti obcházejí jezera a masivy; vypnutí
     *                     vrací přímkovou segmentaci (kill-switch)
     * @param plannedActions kopací hrany v plánu cesty (souvislé tunely místo
     *                       reaktivní eskalace) – aktivují se až po selhání
     *                       pěšího plánu a jen s {@code ai.terraforming};
     *                       vypnutí vrací čistě reaktivní assist (kill-switch)
     */
    public record Pathfinding(int nodeBudget, int timeBudgetMs, boolean farCorridor,
                              boolean plannedActions) {
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
