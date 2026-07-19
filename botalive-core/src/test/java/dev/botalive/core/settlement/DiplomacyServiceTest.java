package dev.botalive.core.settlement;

import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy diplomacie sídel – napětí z křivd, chladnutí, vyhlášení války podle
 * povahy starosty, únava z války a příměří. Bez databáze (repository = null),
 * bez manageru botů (rozhodovací jádro se volá přímo) a s ovládaným časem.
 */
class DiplomacyServiceTest {

    private static final String WORLD = "world";
    private static final BlockPos SITE_A = new BlockPos(100, 64, 100);
    private static final BlockPos SITE_B = new BlockPos(400, 64, 400);

    private static final double NEUTRAL_MILITANCY = 0.5;

    private long now;
    private SettlementService settlements;
    private DiplomacyService service;

    private final UUID founderA = new UUID(0, 1);
    private final UUID founderB = new UUID(0, 2);

    private long villageA;
    private long villageB;

    @BeforeEach
    void setUp() {
        now = 1_000_000L;
        settlements = new SettlementService(settlementConfig(), null, () -> now);
        service = new DiplomacyService(warConfig(true), pvpConfig(),
                settlements, null, () -> now);
        villageA = found(founderA, SITE_A, 42L);
        villageB = found(founderB, SITE_B, 43L);
    }

    private static BotAliveConfig.Settlement settlementConfig() {
        return new BotAliveConfig.Settlement(true, 12, 8, 200, 150, 0.30, 0.60,
                30, true, true, 0, 2, warConfig(true));
    }

    private static BotAliveConfig.War warConfig(boolean enabled) {
        // declare-threshold 4.0, theft 1.5, assault 0.75, decay 0.5/h,
        // min-members 1, raid-size 2, raid-cooldown 5 min, weariness 2 padlí,
        // max-war 6 h, truce 2 h, bez reparací (bez manageru není kdo platí).
        return new BotAliveConfig.War(enabled, 4.0, 1.5, 0.75, 0.5, 1, 2, 5,
                2, 6, 2, false, 40.0);
    }

    private static BotAliveConfig.Pvp pvpConfig() {
        return new BotAliveConfig.Pvp(true, false, true, true, 24, 2);
    }

    private long found(UUID founder, BlockPos site, long seed) {
        SocialView view = new SocialView(founder, "Bot" + founder, WORLD, site,
                0.7, 0.5, null, Map.of(), Map.of(), Map.of());
        var info = settlements.foundSettlement(view, site,
                site.offset(-2, 0, -2), Cardinal.NORTH, seed);
        assertTrue(info.isPresent());
        return info.get().id();
    }

    private void thefts(int count) {
        for (int i = 0; i < count; i++) {
            service.noteOffense(founderA, founderB, DiplomacyService.Offense.THEFT);
        }
    }

    private DiplomacyService.RelationInfo relation() {
        List<DiplomacyService.RelationInfo> all = service.allRelations();
        assertEquals(1, all.size());
        return all.getFirst();
    }

    // ------------------------------------------------------------------ napětí

    @Test
    void krivdaMeziVesnicemiZvedaNapeti() {
        service.noteOffense(founderA, founderB, DiplomacyService.Offense.THEFT);
        assertEquals(1.5, relation().tension(), 1e-9);
        service.noteOffense(founderB, founderA, DiplomacyService.Offense.ASSAULT);
        assertEquals(2.25, relation().tension(), 1e-9);
    }

    @Test
    void krivdaUvnitrVesniceNapetiNezveda() {
        service.noteOffense(founderA, founderA, DiplomacyService.Offense.THEFT);
        assertTrue(service.allRelations().isEmpty());
    }

    @Test
    void krivdaBotaBezVesniceSeIgnoruje() {
        service.noteOffense(founderA, new UUID(0, 99), DiplomacyService.Offense.THEFT);
        assertTrue(service.allRelations().isEmpty());
    }

    @Test
    void napetiCasemChladne() {
        thefts(2); // 3.0
        now += 2 * 3_600_000L; // dvě hodiny → −1.0
        assertEquals(2.0, relation().tension(), 1e-9);
    }

    @Test
    void vypnutaDiplomacieNicNemeri() {
        var disabled = new DiplomacyService(warConfig(false), pvpConfig(),
                settlements, null, () -> now);
        disabled.noteOffense(founderA, founderB, DiplomacyService.Offense.THEFT);
        assertTrue(disabled.allRelations().isEmpty());
    }

    // ------------------------------------------------------------------- válka

    @Test
    void starostaVyhlasiValkuAzNadPrahem() {
        thefts(2); // 3.0 < 4.0×(1.5−0.5)
        assertTrue(service.mayorTick(villageA, NEUTRAL_MILITANCY,
                settlements.all()).isEmpty());
        assertEquals(DiplomacyService.WarState.NEUTRAL, relation().state());

        thefts(1); // 4.5 ≥ 4.0
        var outcomes = service.mayorTick(villageA, NEUTRAL_MILITANCY, settlements.all());
        assertEquals(1, outcomes.size());
        assertEquals(DiplomacyService.Outcome.Kind.WAR_DECLARED,
                outcomes.getFirst().kind());
        assertEquals(DiplomacyService.WarState.WAR, relation().state());
        assertTrue(service.isWarEnemy(founderA, founderB));
        String otherName = relation().aId() == villageA
                ? relation().bName() : relation().aName();
        assertEquals(List.of(otherName), service.atWarWith(villageA));
    }

    @Test
    void bojovnyStarostaVyhlasujeDrive() {
        thefts(2); // 3.0 – neutrálnímu málo, fanatikovi (militance 1.0 → práh 2.0) dost
        var outcomes = service.mayorTick(villageA, 1.0, settlements.all());
        assertEquals(1, outcomes.size());
        assertEquals(DiplomacyService.WarState.WAR, relation().state());
    }

    @Test
    void miroumilovnyStarostaValkuNevyhlasi() {
        thefts(3); // 4.5 – pacifista (militance 0 → práh 6.0) nevyhlašuje
        assertTrue(service.mayorTick(villageA, 0.0, settlements.all()).isEmpty());
        assertEquals(DiplomacyService.WarState.NEUTRAL, relation().state());
    }

    @Test
    void behemValkyNapetiDalNeroste() {
        thefts(3);
        service.mayorTick(villageA, NEUTRAL_MILITANCY, settlements.all());
        double atWar = relation().tension();
        thefts(5);
        assertEquals(atWar, relation().tension(), 1e-9);
    }

    // ---------------------------------------------------------------- příměří

    private void declareWar() {
        thefts(3);
        var outcomes = service.mayorTick(villageA, NEUTRAL_MILITANCY, settlements.all());
        assertEquals(1, outcomes.size());
    }

    @Test
    void padliSePricitajiJenVeValceSNedavnymNajezdem() {
        declareWar();
        // Bez nájezdové vlny se smrt nepřičítá (mohla být nesouvisející).
        service.noteDeath(founderA);
        assertEquals(0, relation().deathsA() + relation().deathsB());

        now += 5 * 60_000L; // cooldown vlny uplynul
        service.mayorTick(villageA, NEUTRAL_MILITANCY, settlements.all());
        service.noteDeath(founderA);
        assertEquals(1, relation().deathsA() + relation().deathsB());
    }

    @Test
    void unavenyStarostaDojednaPrimeri() {
        declareWar();
        now += 5 * 60_000L;
        service.mayorTick(villageA, NEUTRAL_MILITANCY, settlements.all()); // vlna
        service.noteDeath(founderA);
        service.noteDeath(founderA); // weariness 2 dosažena

        var outcomes = service.mayorTick(villageA, NEUTRAL_MILITANCY, settlements.all());
        assertEquals(2, outcomes.size());
        assertEquals(DiplomacyService.Outcome.Kind.TRUCE_OFFERED,
                outcomes.get(0).kind());
        assertEquals(DiplomacyService.Outcome.Kind.TRUCE_AGREED,
                outcomes.get(1).kind());
        assertEquals(DiplomacyService.WarState.TRUCE, relation().state());
        assertFalse(service.isWarEnemy(founderA, founderB));
        assertTrue(relation().tension() <= 4.0 * 0.4 + 1e-9,
                "příměří sráží napětí pod práh");
    }

    @Test
    void pretahovanaValkaKonciPrimerimIBezPadlych() {
        declareWar();
        now += 7 * 3_600_000L; // přes max-war-hours
        var outcomes = service.mayorTick(villageA, NEUTRAL_MILITANCY, settlements.all());
        assertEquals(DiplomacyService.WarState.TRUCE, relation().state());
        assertEquals(2, outcomes.size());
    }

    @Test
    void primeriVyprsiDoNeutrality() {
        declareWar();
        now += 7 * 3_600_000L;
        service.mayorTick(villageA, NEUTRAL_MILITANCY, settlements.all()); // příměří
        now += 3 * 3_600_000L; // truce-hours = 2
        service.mayorTick(villageA, NEUTRAL_MILITANCY, settlements.all());
        assertEquals(DiplomacyService.WarState.NEUTRAL, relation().state());
    }

    // ------------------------------------------------------------------ úklid

    @Test
    void vztahZanikleVesniceSeUklidi() {
        thefts(2);
        assertEquals(1, service.allRelations().size());
        settlements.removeBot(founderB); // poslední člen → vesnice B zaniká
        service.mayorTick(villageA, NEUTRAL_MILITANCY, settlements.all());
        assertTrue(service.allRelations().isEmpty());
    }
}
