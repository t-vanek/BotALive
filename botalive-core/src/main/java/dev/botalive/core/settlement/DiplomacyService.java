package dev.botalive.core.settlement;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.event.SettlementTruceEvent;
import dev.botalive.api.event.SettlementWarDeclaredEvent;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.bot.BotManagerImpl;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.persistence.BotRepository;
import dev.botalive.core.settlement.SettlementService.SettlementInfo;
import dev.botalive.core.util.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Diplomacie sídel: napětí, války a příměří mezi vesnicemi botů.
 *
 * <p>Vztahy jsou <b>emergentní</b> – žádný skript nerozhoduje, kdo s kým
 * válčí. Křivdy mezi členy různých vesnic (odhalená krádež z truhly, napadení)
 * zvedají napětí dvojice sídel; napětí samovolně chladne. Když přeteče práh,
 * rozhoduje <b>povaha starosty</b>: bojovný starosta vyhlásí válku dřív,
 * trpělivý ji nevyhlásí vůbec. Válka znamená nájezdy – starosta vybírá
 * nejbojovnější členy a posílá je na cizí náves; obranu napadených svolává
 * stávající PvP mašinerie ({@code PvpCoordinator}), takže se vesnice brání
 * „zadarmo". Padlí zvyšují únavu z války, dokud unavený starosta nenavrhne
 * příměří (poražený platí reparace z peněženky starosty).</p>
 *
 * <p>Bezpečnostní mantinely: války jsou výhradně mezi boty (hráčů se nikdy
 * netýkají), nájezdy respektují sekci {@code pvp} – bez {@code pvp.enabled}
 * a {@code pvp.attack-bots} se vede jen „studená válka" (napětí a vyhlášení,
 * žádné boje). Rozhodování běží na tick vláknech botů nad snímky sídel,
 * mutace stavu jsou {@code synchronized} po vzoru {@code SettlementService};
 * aktivní rozkazy k nájezdu žijí jen v paměti (restart je korektně zruší).</p>
 */
public final class DiplomacyService {

    /** Jak dlouho platí rozkaz k nájezdu (pak se raider vrací domů). */
    private static final long RAID_CALL_TTL_MS = 5 * 60_000;

    /** Do kdy po nájezdu se smrt člena počítá jako válečná ztráta. */
    private static final long DEATH_ATTRIBUTION_MS = 10 * 60_000;

    /** Nejkratší rozestup dvou návrhů příměří (odmítnutý návrh se opakuje). */
    private static final long TRUCE_RETRY_MS = 10 * 60_000;

    /** První nájezd přichází chvíli po vyhlášení, ne okamžitě. */
    private static final long FIRST_RAID_DELAY_MS = 2 * 60_000;

    /** Minimální odvaha člena, aby ho starosta poslal na nájezd. */
    private static final double RAIDER_MIN_COURAGE = 0.4;

    /** Stav vztahu dvou sídel. */
    public enum WarState { NEUTRAL, WAR, TRUCE }

    /**
     * Křivda mezi členy různých sídel – zdroj napětí.
     */
    public enum Offense { THEFT, ASSAULT }

    /**
     * Rozkaz k nájezdu pro konkrétního bota.
     *
     * @param fromSettlementId   id vysílající vesnice
     * @param targetSettlementId id cílové vesnice
     * @param targetName         jméno cílové vesnice
     * @param world              svět cílové vesnice
     * @param rally              náves cílové vesnice (shromaždiště nájezdu)
     * @param expiresAtMs        konec platnosti rozkazu
     */
    public record RaidCall(long fromSettlementId, long targetSettlementId,
                           String targetName, String world, BlockPos rally,
                           long expiresAtMs) {
    }

    /**
     * Snímek vztahu dvou sídel pro příkazy a diagnostiku.
     *
     * @param aId        id sídla A (menší id dvojice)
     * @param aName      jméno sídla A
     * @param bId        id sídla B
     * @param bName      jméno sídla B
     * @param tension    aktuální napětí
     * @param state      stav vztahu
     * @param stateSince od kdy stav platí (epoch ms)
     * @param truceUntil konec příměří (epoch ms; 0 mimo příměří)
     * @param deathsA    padlí strany A v běžící/poslední válce
     * @param deathsB    padlí strany B
     */
    public record RelationInfo(long aId, String aName, long bId, String bName,
                               double tension, WarState state, long stateSince,
                               long truceUntil, int deathsA, int deathsB) {
    }

    /** Uspořádaná dvojice id sídel – vztah existuje právě jednou. */
    private record PairKey(long a, long b) {
        static PairKey of(long x, long y) {
            return x < y ? new PairKey(x, y) : new PairKey(y, x);
        }
    }

    /** Živý stav vztahu (mutable; přístup jen pod zámkem služby). */
    private static final class Relation {
        double tension;
        WarState state = WarState.NEUTRAL;
        long stateSince;
        long truceUntil;
        int deathsA;
        int deathsB;
        long lastDecayAt;
        /** Kdy smí vyrazit další nájezdová vlna (plánování). */
        long nextRaidAt;
        /** Kdy naposledy vlna vyrazila (atribuce padlých). */
        long lastWaveAt;
        long lastTruceOfferAt;
    }

    private final BotAliveConfig.War config;
    private final BotAliveConfig.Pvp pvpConfig;
    private final SettlementService settlements;
    private final BotRepository repository;
    private final LongSupplier clock;

    private volatile BotManagerImpl botManager;

    private final Map<PairKey, Relation> relations = new HashMap<>();

    /** Rozkazy k nájezdům – čtou se bez zámku z utility() cílů. */
    private final Map<UUID, RaidCall> raidCalls = new ConcurrentHashMap<>();

    /**
     * @param config      konfigurace válek ({@code settlement.war})
     * @param pvpConfig   konfigurace PvP (mantinely nájezdů)
     * @param settlements služba sídel (členství, návsi, starostové)
     * @param repository  persistence; {@code null} = bez databáze (testy)
     */
    public DiplomacyService(BotAliveConfig.War config, BotAliveConfig.Pvp pvpConfig,
                            SettlementService settlements, BotRepository repository) {
        this(config, pvpConfig, settlements, repository, System::currentTimeMillis);
    }

    /**
     * @param clock zdroj času (pro testy)
     */
    DiplomacyService(BotAliveConfig.War config, BotAliveConfig.Pvp pvpConfig,
                     SettlementService settlements, BotRepository repository,
                     LongSupplier clock) {
        this.config = config;
        this.pvpConfig = pvpConfig;
        this.settlements = settlements;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Připojí manager botů (bootstrap – manager vzniká později než služba).
     *
     * @param manager manager botů
     */
    public void attach(BotManagerImpl manager) {
        this.botManager = manager;
    }

    /**
     * Synchronně načte vztahy z databáze (bootstrap, po
     * {@code SettlementService.load()}).
     */
    public synchronized void load() {
        if (repository == null) {
            return;
        }
        for (BotRepository.SettlementRelationRow row
                : repository.loadSettlementRelations().join()) {
            Relation relation = new Relation();
            relation.tension = row.tension();
            relation.state = parseState(row.state());
            relation.stateSince = row.stateSince();
            relation.truceUntil = row.truceUntil();
            relation.deathsA = row.deathsA();
            relation.deathsB = row.deathsB();
            relation.lastDecayAt = row.stateSince();
            relations.put(PairKey.of(row.settlementA(), row.settlementB()), relation);
        }
    }

    private static WarState parseState(String name) {
        try {
            return WarState.valueOf(name);
        } catch (IllegalArgumentException e) {
            return WarState.NEUTRAL;
        }
    }

    // ================================================================ vstupy

    /**
     * Zaznamená křivdu mezi dvěma boty. Pokud patří do různých vesnic ve
     * stejném světě, zvedne napětí dvojice; během války se napětí dál
     * nezvedá (už se válčí) a během příměří jen poloviční vahou.
     *
     * <p>Volá se z tick i region vláken – hráči a boti bez vesnice jsou
     * levně odfiltrováni členským indexem sídel.</p>
     *
     * @param victim   UUID poškozeného bota
     * @param offender UUID pachatele (bot; hráči se tiše ignorují)
     * @param offense  druh křivdy
     */
    public void noteOffense(UUID victim, UUID offender, Offense offense) {
        if (!config.enabled() || victim == null || offender == null
                || victim.equals(offender)) {
            return;
        }
        Optional<SettlementInfo> victimHome = settlements.settlementOf(victim);
        Optional<SettlementInfo> offenderHome = settlements.settlementOf(offender);
        if (victimHome.isEmpty() || offenderHome.isEmpty()) {
            return;
        }
        SettlementInfo a = victimHome.get();
        SettlementInfo b = offenderHome.get();
        if (a.id() == b.id() || !a.world().equals(b.world())) {
            return;
        }
        double weight = switch (offense) {
            case THEFT -> config.theftWeight();
            case ASSAULT -> config.assaultWeight();
        };
        if (weight <= 0) {
            return;
        }
        raiseTension(a.id(), b.id(), weight);
    }

    private synchronized void raiseTension(long aId, long bId, double weight) {
        long now = clock.getAsLong();
        PairKey key = PairKey.of(aId, bId);
        Relation relation = relations.computeIfAbsent(key, k -> {
            Relation fresh = new Relation();
            fresh.stateSince = now;
            fresh.lastDecayAt = now;
            return fresh;
        });
        applyDecay(relation, now);
        if (relation.state == WarState.WAR) {
            return; // válka už běží, křivdy jsou její součástí
        }
        if (relation.state == WarState.TRUCE) {
            weight *= 0.5; // příměří tlumí, ale nemaže
        }
        relation.tension = Math.min(config.declareThreshold() * 2, relation.tension + weight);
        persist(key, relation);
    }

    /**
     * Zaznamená smrt bota. Pokud jeho vesnice právě válčí a smrt lze
     * přičíst bojům (aktivní rozkaz k nájezdu, nebo nedávný nájezd v okolí
     * vztahu), zvýší válečné ztráty jeho strany.
     *
     * @param botId UUID padlého bota
     */
    public synchronized void noteDeath(UUID botId) {
        if (!config.enabled() || botId == null) {
            return;
        }
        OptionalLong sid = settlements.settlementIdOf(botId);
        if (sid.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        RaidCall call = raidCalls.get(botId);
        PairKey key = null;
        if (call != null) {
            key = PairKey.of(call.fromSettlementId(), call.targetSettlementId());
        } else {
            // Obránce: najdi válku vesnice s nedávným nájezdem.
            long bestRaid = 0;
            for (Map.Entry<PairKey, Relation> entry : relations.entrySet()) {
                PairKey candidate = entry.getKey();
                Relation relation = entry.getValue();
                if (relation.state != WarState.WAR
                        || (candidate.a() != sid.getAsLong()
                                && candidate.b() != sid.getAsLong())) {
                    continue;
                }
                if (relation.lastWaveAt > 0
                        && now - relation.lastWaveAt <= DEATH_ATTRIBUTION_MS
                        && relation.lastWaveAt > bestRaid) {
                    bestRaid = relation.lastWaveAt;
                    key = candidate;
                }
            }
        }
        if (key == null) {
            return;
        }
        Relation relation = relations.get(key);
        if (relation == null || relation.state != WarState.WAR) {
            return;
        }
        if (key.a() == sid.getAsLong()) {
            relation.deathsA++;
        } else {
            relation.deathsB++;
        }
        persist(key, relation);
    }

    // ======================================================= starostův tick

    /**
     * Výsledek starostova rozhodování – podklad pro hlášky a API eventy.
     * Oddělené od rozhodovacího jádra, aby šla logika testovat bez bota
     * (vzor {@code CohesionAction}).
     *
     * @param kind        druh rozhodnutí
     * @param otherId     id protistrany
     * @param otherName   jméno protistrany
     * @param reparations zaplacené reparace (jen u {@code TRUCE_AGREED})
     * @param tension     napětí v okamžiku rozhodnutí
     */
    record Outcome(Kind kind, long otherId, String otherName,
                   double reparations, double tension) {
        enum Kind { WAR_DECLARED, TRUCE_OFFERED, TRUCE_AGREED }
    }

    /**
     * Pravidelné diplomatické rozhodování. Volá se z ticku každého bota
     * (řídce); skutečně rozhoduje jen úřadující starosta své vesnice –
     * vyhlašuje války, vysílá nájezdy a vyjednává příměří. Hlášky a API
     * eventy se dějí tady, nad výsledky rozhodovacího jádra.
     *
     * @param bot bot, jehož tick právě běží
     */
    public void maybeTick(Bot bot) {
        if (!config.enabled() || botManager == null) {
            return;
        }
        OptionalLong sidOpt = settlements.settlementIdOf(bot.id());
        if (sidOpt.isEmpty()) {
            return;
        }
        long sid = sidOpt.getAsLong();
        List<SettlementInfo> snapshot = settlements.all();
        SettlementInfo own = byId(snapshot, sid);
        if (own == null || !bot.id().equals(actingMayor(own))) {
            return;
        }
        double militancy = bot.personality().trait(Trait.AGGRESSION) * 0.6
                + bot.personality().trait(Trait.COURAGE) * 0.4;
        for (Outcome outcome : mayorTick(sid, militancy, snapshot)) {
            switch (outcome.kind()) {
                case WAR_DECLARED -> {
                    ((BotContext) bot).chat().sayFrom(
                            PhraseCategory.WAR_DECLARED, outcome.otherName());
                    new SettlementWarDeclaredEvent(bot, own.id(), own.name(),
                            outcome.otherId(), outcome.otherName(),
                            outcome.tension()).callEvent();
                }
                case TRUCE_OFFERED -> ((BotContext) bot).chat().sayFrom(
                        PhraseCategory.WAR_TRUCE_OFFER, outcome.otherName());
                case TRUCE_AGREED -> {
                    ((BotContext) bot).chat().sayFrom(
                            PhraseCategory.WAR_TRUCE_AGREED, outcome.otherName());
                    new SettlementTruceEvent(bot, own.id(), own.name(),
                            outcome.otherId(), outcome.otherName(),
                            outcome.reparations()).callEvent();
                }
            }
        }
    }

    /**
     * Rozhodovací jádro starosty – bez bota, bez chatu, bez Bukkit eventů
     * (jednotková testovatelnost).
     *
     * @param ownId     id starostovy vesnice
     * @param militancy bojovnost starosty (agrese 0.6 + odvaha 0.4)
     * @param snapshot  snímek všech sídel
     * @return rozhodnutí k ohlášení
     */
    synchronized List<Outcome> mayorTick(long ownId, double militancy,
                                         List<SettlementInfo> snapshot) {
        long now = clock.getAsLong();
        pruneDeadSettlements(snapshot);
        SettlementInfo own = byId(snapshot, ownId);
        if (own == null) {
            return List.of();
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Map.Entry<PairKey, Relation> entry : List.copyOf(relations.entrySet())) {
            PairKey key = entry.getKey();
            if (key.a() != ownId && key.b() != ownId) {
                continue;
            }
            Relation relation = entry.getValue();
            SettlementInfo other = byId(snapshot,
                    key.a() == ownId ? key.b() : key.a());
            if (other == null) {
                continue; // zaniklé sídlo uklidí prune při dalším ticku
            }
            applyDecay(relation, now);
            switch (relation.state) {
                case NEUTRAL ->
                        maybeDeclareWar(own, other, key, relation, now, militancy)
                                .ifPresent(outcomes::add);
                case WAR -> tickWar(own, other, key, relation, now)
                        .forEach(outcomes::add);
                case TRUCE -> {
                    if (now >= relation.truceUntil) {
                        relation.state = WarState.NEUTRAL;
                        relation.stateSince = now;
                        relation.truceUntil = 0;
                        persist(key, relation);
                    }
                }
            }
        }
        return outcomes;
    }

    private Optional<Outcome> maybeDeclareWar(SettlementInfo own, SettlementInfo other,
                                              PairKey key, Relation relation, long now,
                                              double militancy) {
        if (own.members().size() < config.minMembers()
                || other.members().size() < config.minMembers()
                || !own.world().equals(other.world())) {
            return Optional.empty();
        }
        // Bojovný starosta vyhlašuje dřív (militance 1.0 → 0.5× práh),
        // mírumilovný později nebo vůbec (militance 0 → 1.5× práh).
        double effectiveThreshold = config.declareThreshold() * (1.5 - militancy);
        if (relation.tension < effectiveThreshold) {
            return Optional.empty();
        }
        relation.state = WarState.WAR;
        relation.stateSince = now;
        relation.deathsA = 0;
        relation.deathsB = 0;
        relation.nextRaidAt = now + FIRST_RAID_DELAY_MS;
        relation.lastWaveAt = 0;
        persist(key, relation);
        return Optional.of(new Outcome(Outcome.Kind.WAR_DECLARED,
                other.id(), other.name(), 0, relation.tension));
    }

    private List<Outcome> tickWar(SettlementInfo own, SettlementInfo other,
                                  PairKey key, Relation relation, long now) {
        int ownDeaths = key.a() == own.id() ? relation.deathsA : relation.deathsB;
        long warDurationMs = now - relation.stateSince;
        boolean weary = ownDeaths >= config.wearinessDeaths();
        boolean overtime = warDurationMs >= hoursToMs(config.maxWarHours());
        if (weary || overtime) {
            return maybeTruce(own, other, key, relation, now, overtime);
        }
        if (now >= relation.nextRaidAt) {
            // Vlna se odbije i bez vyslaných nájezdníků (nikdo vhodný) –
            // cooldown se spotřebuje a padlí z ní se přičítají válce.
            relation.lastWaveAt = now;
            relation.nextRaidAt = now + minutesToMs(config.raidCooldownMinutes());
            launchRaid(own, other, now);
            persist(key, relation);
        }
        return List.of();
    }

    private List<Outcome> maybeTruce(SettlementInfo own, SettlementInfo other,
                                     PairKey key, Relation relation, long now,
                                     boolean forced) {
        if (now - relation.lastTruceOfferAt < TRUCE_RETRY_MS) {
            return List.of();
        }
        relation.lastTruceOfferAt = now;
        Outcome offered = new Outcome(Outcome.Kind.TRUCE_OFFERED,
                other.id(), other.name(), 0, relation.tension);
        if (!forced && !enemyAccepts(other, key, relation)) {
            persist(key, relation);
            return List.of(offered);
        }
        double paid = settleReparations(own, other, key, relation);
        relation.state = WarState.TRUCE;
        relation.stateSince = now;
        relation.truceUntil = now + hoursToMs(config.truceHours());
        relation.tension = Math.min(relation.tension, config.declareThreshold() * 0.4);
        persist(key, relation);
        clearRaidsBetween(own.id(), other.id());
        return List.of(offered, new Outcome(Outcome.Kind.TRUCE_AGREED,
                other.id(), other.name(), paid, relation.tension));
    }

    /** Přijme protistrana příměří? Rozhoduje její únava a povaha starosty. */
    private boolean enemyAccepts(SettlementInfo other, PairKey key, Relation relation) {
        int otherDeaths = key.a() == other.id() ? relation.deathsA : relation.deathsB;
        if (otherDeaths * 2 >= config.wearinessDeaths()) {
            return true; // i protistrana krvácí
        }
        Bot otherMayor = resolve(actingMayor(other));
        if (otherMayor == null) {
            return true; // bez starosty není kdo by odmítl
        }
        return otherMayor.personality().trait(Trait.PATIENCE) >= 0.35;
    }

    /** Poražený (více padlých) platí vítězi z peněženky starosty. */
    private double settleReparations(SettlementInfo own, SettlementInfo other,
                                     PairKey key, Relation relation) {
        if (!config.reparations() || config.reparationsMax() <= 0) {
            return 0;
        }
        int deathsOwn = key.a() == own.id() ? relation.deathsA : relation.deathsB;
        int deathsOther = key.a() == other.id() ? relation.deathsA : relation.deathsB;
        if (deathsOwn == deathsOther) {
            return 0; // remíza, nikdo neplatí
        }
        SettlementInfo loser = deathsOwn > deathsOther ? own : other;
        SettlementInfo winner = loser == own ? other : own;
        Bot loserMayor = resolve(actingMayor(loser));
        Bot winnerMayor = resolve(actingMayor(winner));
        if (loserMayor == null || winnerMayor == null) {
            return 0;
        }
        double amount = Math.min(config.reparationsMax(),
                Math.floor(loserMayor.wallet().balance()));
        if (amount <= 0 || !loserMayor.wallet().withdraw(amount, "válečné reparace")) {
            return 0;
        }
        winnerMayor.wallet().deposit(amount, "válečné reparace");
        return amount;
    }

    private void launchRaid(SettlementInfo own, SettlementInfo other, long now) {
        // Bez PvP mezi boty se válčí jen studeně – žádné nájezdy.
        if (!pvpConfig.enabled() || !pvpConfig.attackBots()
                || !own.world().equals(other.world())) {
            return;
        }
        BotManagerImpl manager = botManager;
        if (manager == null) {
            return;
        }
        List<Bot> raiders = new ArrayList<>();
        for (SettlementService.MemberInfo member : own.members()) {
            Bot candidate = resolve(member.botId());
            if (candidate == null || candidate.paused()
                    || !candidate.snapshot().online()
                    || candidate.personality().trait(Trait.COURAGE) < RAIDER_MIN_COURAGE) {
                continue;
            }
            raiders.add(candidate);
        }
        raiders.sort((x, y) -> Double.compare(militancy(y), militancy(x)));
        int sent = 0;
        for (Bot raider : raiders) {
            if (sent >= config.raidSize()) {
                break;
            }
            raidCalls.put(raider.id(), new RaidCall(own.id(), other.id(),
                    other.name(), other.world(), other.center(),
                    now + RAID_CALL_TTL_MS));
            sent++;
        }
    }

    private static double militancy(Bot bot) {
        return bot.personality().trait(Trait.AGGRESSION) * 0.6
                + bot.personality().trait(Trait.COURAGE) * 0.4;
    }

    // ================================================================ dotazy

    /**
     * @param botId UUID bota
     * @return platný rozkaz k nájezdu, pokud ho starosta vydal
     */
    public Optional<RaidCall> raidCall(UUID botId) {
        RaidCall call = raidCalls.get(botId);
        if (call == null || clock.getAsLong() > call.expiresAtMs()) {
            raidCalls.remove(botId);
            return Optional.empty();
        }
        return Optional.of(call);
    }

    /**
     * Zruší rozkaz k nájezdu (splněn/nesplnitelný).
     *
     * @param botId UUID bota
     */
    public void clearRaidCall(UUID botId) {
        raidCalls.remove(botId);
    }

    /**
     * Jsou vesnice obou botů ve válce? (Válečný nepřítel je legitimní cíl
     * nájezdu.)
     *
     * @param botId UUID prvního bota
     * @param otherId UUID druhého bota
     * @return {@code true}, pokud jsou jejich vesnice ve válce
     */
    public synchronized boolean isWarEnemy(UUID botId, UUID otherId) {
        if (!config.enabled() || botId == null || otherId == null) {
            return false;
        }
        OptionalLong a = settlements.settlementIdOf(botId);
        OptionalLong b = settlements.settlementIdOf(otherId);
        if (a.isEmpty() || b.isEmpty() || a.getAsLong() == b.getAsLong()) {
            return false;
        }
        Relation relation = relations.get(PairKey.of(a.getAsLong(), b.getAsLong()));
        return relation != null && relation.state == WarState.WAR;
    }

    /**
     * @param settlementId id sídla
     * @return jména sídel, se kterými je právě ve válce
     */
    public synchronized List<String> atWarWith(long settlementId) {
        List<String> result = new ArrayList<>();
        List<SettlementInfo> snapshot = settlements.all();
        for (Map.Entry<PairKey, Relation> entry : relations.entrySet()) {
            if (entry.getValue().state != WarState.WAR) {
                continue;
            }
            PairKey key = entry.getKey();
            long otherId;
            if (key.a() == settlementId) {
                otherId = key.b();
            } else if (key.b() == settlementId) {
                otherId = key.a();
            } else {
                continue;
            }
            SettlementInfo other = byId(snapshot, otherId);
            if (other != null) {
                result.add(other.name());
            }
        }
        return result;
    }

    /**
     * @return snímek všech vztahů (pro {@code /botalive diplomacy})
     */
    public synchronized List<RelationInfo> allRelations() {
        long now = clock.getAsLong();
        List<SettlementInfo> snapshot = settlements.all();
        List<RelationInfo> result = new ArrayList<>();
        for (Map.Entry<PairKey, Relation> entry : relations.entrySet()) {
            PairKey key = entry.getKey();
            SettlementInfo a = byId(snapshot, key.a());
            SettlementInfo b = byId(snapshot, key.b());
            if (a == null || b == null) {
                continue;
            }
            Relation relation = entry.getValue();
            applyDecay(relation, now);
            result.add(new RelationInfo(a.id(), a.name(), b.id(), b.name(),
                    relation.tension, relation.state, relation.stateSince,
                    relation.truceUntil, relation.deathsA, relation.deathsB));
        }
        result.sort((x, y) -> Double.compare(y.tension(), x.tension()));
        return result;
    }

    // ================================================================ nitro

    /** Napětí chladne s časem; počítá se líně při každém dotyku vztahu. */
    private void applyDecay(Relation relation, long now) {
        if (relation.lastDecayAt <= 0) {
            relation.lastDecayAt = now;
            return;
        }
        long elapsed = now - relation.lastDecayAt;
        if (elapsed <= 0) {
            return;
        }
        double hours = elapsed / 3_600_000.0;
        relation.tension = Math.max(0, relation.tension - config.decayPerHour() * hours);
        relation.lastDecayAt = now;
    }

    private void pruneDeadSettlements(List<SettlementInfo> snapshot) {
        Set<Long> alive = new HashSet<>();
        for (SettlementInfo info : snapshot) {
            alive.add(info.id());
        }
        for (PairKey key : List.copyOf(relations.keySet())) {
            if (alive.contains(key.a()) && alive.contains(key.b())) {
                continue;
            }
            relations.remove(key);
            if (repository != null) {
                long dead = alive.contains(key.a()) ? key.b() : key.a();
                repository.deleteSettlementRelations(dead);
            }
        }
    }

    private void clearRaidsBetween(long aId, long bId) {
        raidCalls.values().removeIf(call ->
                (call.fromSettlementId() == aId && call.targetSettlementId() == bId)
                        || (call.fromSettlementId() == bId && call.targetSettlementId() == aId));
    }

    /** Úřadující starosta: odvozený starosta, jinak první člen. */
    private static UUID actingMayor(SettlementInfo info) {
        if (info.mayor() != null) {
            return info.mayor();
        }
        return info.members().isEmpty() ? null : info.members().get(0).botId();
    }

    private Bot resolve(UUID botId) {
        BotManagerImpl manager = botManager;
        if (manager == null || botId == null) {
            return null;
        }
        return manager.byId(botId).orElse(null);
    }

    private static SettlementInfo byId(List<SettlementInfo> snapshot, long id) {
        for (SettlementInfo info : snapshot) {
            if (info.id() == id) {
                return info;
            }
        }
        return null;
    }

    private void persist(PairKey key, Relation relation) {
        if (repository == null) {
            return;
        }
        repository.upsertSettlementRelation(new BotRepository.SettlementRelationRow(
                key.a(), key.b(), relation.tension, relation.state.name(),
                relation.stateSince, relation.truceUntil,
                relation.deathsA, relation.deathsB));
    }

    private static long minutesToMs(int minutes) {
        return minutes * 60_000L;
    }

    private static long hoursToMs(int hours) {
        return hours * 3_600_000L;
    }
}
