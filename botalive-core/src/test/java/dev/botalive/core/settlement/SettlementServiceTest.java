package dev.botalive.core.settlement;

import dev.botalive.core.util.Cardinal;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy rozhodovací logiky vesnic – zakládání, vstup, parcely, roztržky
 * a stěhování. Bez databáze (repository = null) a s ovládaným časem.
 */
class SettlementServiceTest {

    private static final String WORLD = "world";
    private static final BlockPos HOME_SITE = new BlockPos(100, 64, 100);

    private long now;
    private SettlementService service;

    private final UUID founder = new UUID(0, 1);
    private final UUID joiner = new UUID(0, 2);
    private final UUID third = new UUID(0, 3);

    @BeforeEach
    void setUp() {
        now = 1_000_000L;
        service = new SettlementService(config(), null, () -> now);
    }

    private static BotAliveConfig.Settlement config() {
        return new BotAliveConfig.Settlement(true, 12, 8, 200, 150, 0.30, 0.60, 30, true, true, 0, 2);
    }

    private SocialView view(UUID botId, BlockPos position, double sociability,
                            BlockPos housePos, Map<UUID, Double> friends,
                            Map<UUID, Double> enemies, Map<UUID, Long> enemyAt) {
        return new SocialView(botId, "Bot" + botId.getLeastSignificantBits(), WORLD,
                position, sociability, 0.5, housePos, friends, enemies, enemyAt);
    }

    private SocialView plainView(UUID botId, double sociability) {
        return view(botId, HOME_SITE, sociability, null, Map.of(), Map.of(), Map.of());
    }

    /** Založí vesnici zakladatelem s návsí na HOME_SITE a vrátí ji. */
    private SettlementService.SettlementInfo foundVillage() {
        Optional<SettlementService.SettlementInfo> info = service.foundSettlement(
                plainView(founder, 0.7), HOME_SITE, HOME_SITE.offset(-2, 0, -2),
                Cardinal.NORTH, 42L);
        assertTrue(info.isPresent());
        return info.get();
    }

    // ------------------------------------------------------------- růst sídla

    /** Postaví osadu se 4 dostavěnými domy (zakladatel + 3 osadníci). */
    private SettlementService.SettlementInfo villageWithFourHouses() {
        var village = foundVillage();
        service.houseFinished(founder);
        UUID[] settlers = {joiner, third, new UUID(0, 4)};
        for (UUID settler : settlers) {
            var settlerView = view(settler, HOME_SITE.offset(30, 0, 0), 0.7, null,
                    Map.of(founder, 0.6), Map.of(), Map.of());
            assertTrue(service.join(village.id(), settlerView));
            var slot = service.suggestPlots(village.id(), 1).getFirst();
            assertTrue(service.claimPlot(village.id(), settler, slot));
            assertTrue(service.houseFinished(settler).isEmpty(),
                    "bez studny domy samy nepovyšují");
        }
        return village;
    }

    @Test
    void studnaDovrsiVesnici() {
        var village = villageWithFourHouses();
        // Čtyři domy bez studny = pořád osada; sídlo ale už studnu nabízí.
        assertEquals(SettlementTier.OSADA,
                service.settlementOf(founder).orElseThrow().tier());
        var project = service.neededProject(founder);
        assertTrue(project.isPresent());
        assertEquals(SettlementService.ProjectKind.WELL, project.get().kind());

        // První bere; druhému se projekt nenabízí, dokud ho stavitel drží.
        assertTrue(service.claimProject(village.id(),
                SettlementService.ProjectKind.WELL, founder));
        assertTrue(service.neededProject(joiner).isEmpty(),
                "rozdělaný projekt cizího stavitele se nenabízí");
        assertFalse(service.claimProject(village.id(),
                SettlementService.ProjectKind.WELL, joiner));

        // Uvolnění (přerušený cíl) → převezme soused; dokončení povyšuje.
        service.releaseProject(village.id(), SettlementService.ProjectKind.WELL, founder);
        assertTrue(service.claimProject(village.id(),
                SettlementService.ProjectKind.WELL, joiner));
        assertEquals(Optional.of(SettlementTier.VESNICE),
                service.projectFinished(village.id(), SettlementService.ProjectKind.WELL),
                "hotová studna dělá z osady vesnici");
        // Jednorázovost hlášky i dokončení.
        assertTrue(service.projectFinished(village.id(),
                SettlementService.ProjectKind.WELL).isEmpty());
        var info = service.settlementOf(founder).orElseThrow();
        assertEquals(SettlementTier.VESNICE, info.tier());
        assertEquals(4, info.houses());
        // Po studně už sídlo žádný projekt nepotřebuje (sýpka je fáze B2).
        assertTrue(service.neededProject(founder).isEmpty());
    }

    @Test
    void parcelaProjektuSeDomumNenabizi() {
        var village = villageWithFourHouses();
        var project = service.neededProject(founder).orElseThrow();
        for (var slot : service.suggestPlots(village.id(), 50)) {
            assertFalse(slot.origin().equals(project.origin()),
                    "parcela studny nesmí být nabídnuta na dům");
        }
    }

    @Test
    void uvolneniParcelySnizujeSubstanci() {
        foundVillage();
        service.houseFinished(founder);
        assertEquals(1, service.settlementOf(founder).orElseThrow().houses());
        // Dům zanikl (zatopený, zbořený) – parcela se vrací, substance klesá,
        // stupeň se tiše přepočítá (zánik se neslaví hláškou).
        service.releasePlot(founder);
        assertEquals(0, service.settlementOf(founder).orElseThrow().houses());
        assertEquals(SettlementTier.OSADA, service.settlementOf(founder).orElseThrow().tier());
    }

    // ------------------------------------------------------------- planHome

    @Test
    void samotarStaviSam() {
        assertEquals(SettlementService.HomePlan.Kind.SOLO,
                service.planHome(plainView(joiner, 0.1)).kind());
    }

    @Test
    void bezVesnicSeZaklada() {
        assertEquals(SettlementService.HomePlan.Kind.FOUND,
                service.planHome(plainView(founder, 0.7)).kind());
    }

    @Test
    void clenDostaneSvouVesnici() {
        var village = foundVillage();
        var plan = service.planHome(plainView(founder, 0.7));
        assertEquals(SettlementService.HomePlan.Kind.MEMBER, plan.kind());
        assertEquals(village.id(), plan.settlementId());
    }

    @Test
    void kamaradSePridaKVesnici() {
        var village = foundVillage();
        var view = view(joiner, HOME_SITE.offset(30, 0, 0), 0.4, null,
                Map.of(founder, 0.5), Map.of(), Map.of());
        var plan = service.planHome(view);
        assertEquals(SettlementService.HomePlan.Kind.JOIN, plan.kind());
        assertEquals(village.id(), plan.settlementId());
        assertTrue(service.join(plan.settlementId(), view));
        assertEquals(2, service.all().getFirst().members().size());
    }

    @Test
    void velmiSpolecenskySePridaIBezKamaradu() {
        foundVillage();
        var plan = service.planHome(view(joiner, HOME_SITE.offset(30, 0, 0), 0.7,
                null, Map.of(), Map.of(), Map.of()));
        assertEquals(SettlementService.HomePlan.Kind.JOIN, plan.kind());
    }

    @Test
    void mirneSpolecenskyBezKamaraduNevstupuje() {
        foundVillage();
        var plan = service.planHome(view(joiner, HOME_SITE.offset(30, 0, 0), 0.45,
                null, Map.of(), Map.of(), Map.of()));
        // Vesnice je blíž než minimální odstup → zakládat se jde až dál.
        assertEquals(SettlementService.HomePlan.Kind.FOUND_AWAY, plan.kind());
    }

    @Test
    void kNepriteliSeBotNeprida() {
        foundVillage();
        var plan = service.planHome(view(joiner, HOME_SITE.offset(30, 0, 0), 0.7,
                null, Map.of(), Map.of(founder, 0.8), Map.of(founder, now)));
        assertEquals(SettlementService.HomePlan.Kind.FOUND_AWAY, plan.kind());
    }

    @Test
    void plnaVesniceNoveClenyNebere() {
        var config = new BotAliveConfig.Settlement(true, 12, 2, 200, 150, 0.30, 0.60, 30, true, true, 0, 2);
        service = new SettlementService(config, null, () -> now);
        var village = foundVillage();
        assertTrue(service.join(village.id(),
                view(joiner, HOME_SITE, 0.7, null, Map.of(founder, 0.5), Map.of(), Map.of())));
        assertFalse(service.join(village.id(),
                view(third, HOME_SITE, 0.7, null, Map.of(founder, 0.5), Map.of(), Map.of())));
    }

    // ------------------------------------------------------------- parcely

    @Test
    void parcelySeZabirajiOdNavsiAJsouUnikatni() {
        var village = foundVillage();
        var joinerView = view(joiner, HOME_SITE, 0.7, null,
                Map.of(founder, 0.5), Map.of(), Map.of());
        assertTrue(service.join(village.id(), joinerView));
        List<SettlementService.PlotSlot> slots = service.suggestPlots(village.id(), 4);
        assertEquals(4, slots.size());
        assertEquals(1, slots.getFirst().index());
        assertTrue(service.claimPlot(village.id(), joiner, slots.getFirst()));
        // Zabranou parcelu už návrhy nenabízejí.
        List<SettlementService.PlotSlot> next = service.suggestPlots(village.id(), 4);
        assertEquals(2, next.getFirst().index());
        // A bot ji má uloženou.
        assertEquals(1, service.claimedPlot(joiner).orElseThrow().index());
    }

    @Test
    void nepouzitelnaParcelaSePreskakuje() {
        var village = foundVillage();
        service.markPlotUnusable(village.id(), 1);
        assertEquals(2, service.suggestPlots(village.id(), 1).getFirst().index());
    }

    @Test
    void ciziParceluNejdeZabrat() {
        var village = foundVillage();
        var joinerView = view(joiner, HOME_SITE, 0.7, null,
                Map.of(founder, 0.5), Map.of(), Map.of());
        var thirdView = view(third, HOME_SITE, 0.7, null,
                Map.of(founder, 0.5), Map.of(), Map.of());
        assertTrue(service.join(village.id(), joinerView));
        assertTrue(service.join(village.id(), thirdView));
        var slot = service.suggestPlots(village.id(), 1).getFirst();
        assertTrue(service.claimPlot(village.id(), joiner, slot));
        assertFalse(service.claimPlot(village.id(), third, slot));
    }

    // ------------------------------------------------------ soudržnost/roztržky

    @Test
    void roztrzkaVyzeneBotaZVesnice() {
        var village = foundVillage();
        assertTrue(service.join(village.id(),
                view(joiner, HOME_SITE, 0.7, null, Map.of(founder, 0.2), Map.of(), Map.of())));
        now += 6 * 60_000; // krátký odstup od vstupu (roztržka smí dřív než cooldown)

        // Zakladatel bota okradl: čerstvá zášť 0.8 > vazby (0.2 přátelství).
        var angry = view(joiner, HOME_SITE, 0.7, HOME_SITE,
                Map.of(founder, 0.2), Map.of(founder, 0.8), Map.of(founder, now));
        var action = service.checkCohesion(angry);
        assertTrue(action.isPresent());
        assertEquals(SettlementService.CohesionAction.Type.GRUDGE_LEAVE,
                action.get().type());
        assertTrue(action.get().rebuild());
        // Odešel a má si postavit nový dům.
        assertTrue(service.settlementOf(joiner).isEmpty());
        assertTrue(service.consumeRebuild(joiner));
        assertFalse(service.consumeRebuild(joiner), "příznak se čtením maže");
        // Vesnice zůstala zakladateli.
        assertEquals(1, service.all().getFirst().members().size());
    }

    @Test
    void staraZastNevyhani() {
        var village = foundVillage();
        assertTrue(service.join(village.id(),
                view(joiner, HOME_SITE, 0.7, null, Map.of(founder, 0.2), Map.of(), Map.of())));
        now += 6 * 60_000;
        long staleTime = now - 3 * 60 * 60 * 1000L; // zášť stará 3 hodiny
        var view = view(joiner, HOME_SITE, 0.7, HOME_SITE,
                Map.of(founder, 0.2), Map.of(founder, 0.8), Map.of(founder, staleTime));
        assertTrue(service.checkCohesion(view).isEmpty());
    }

    @Test
    void silneVazbyPrezijiZast() {
        var village = foundVillage();
        assertTrue(service.join(village.id(),
                view(joiner, HOME_SITE, 0.7, null, Map.of(founder, 0.3), Map.of(), Map.of())));
        assertTrue(service.join(village.id(),
                view(third, HOME_SITE, 0.7, null, Map.of(), Map.of(), Map.of())));
        now += 6 * 60_000;
        // Zášť 0.6 na třetího, ale kamarádství 0.3 + 0.9 s ostatními drží.
        var view = view(joiner, HOME_SITE, 0.7, HOME_SITE,
                new HashMap<>(Map.of(founder, 0.9)),
                Map.of(third, 0.62), Map.of(third, now));
        assertTrue(service.checkCohesion(view).isEmpty());
    }

    @Test
    void stehovaniZaKamarady() {
        // Vesnice A (jen slabé vazby) a vesnice B s nejlepším kamarádem.
        var villageA = foundVillage();
        assertTrue(service.join(villageA.id(),
                view(joiner, HOME_SITE, 0.6, null, Map.of(founder, 0.1), Map.of(), Map.of())));
        var farSite = HOME_SITE.offset(200, 0, 0);
        var villageB = service.foundSettlement(plainView(third, 0.7), farSite,
                farSite.offset(-2, 0, -2), Cardinal.NORTH, 7L).orElseThrow();
        now += 31 * 60_000; // cooldown stěhování uplynul

        var view = view(joiner, HOME_SITE, 0.6, HOME_SITE,
                Map.of(founder, 0.1, third, 0.8), Map.of(), Map.of());
        var action = service.checkCohesion(view);
        assertTrue(action.isPresent());
        assertEquals(SettlementService.CohesionAction.Type.FOLLOW_FRIEND,
                action.get().type());
        assertEquals(villageB.name(), action.get().settlementName());
        assertEquals(villageB.id(), service.settlementIdOf(joiner).orElseThrow());
        assertTrue(service.consumeRebuild(joiner));
    }

    @Test
    void samotarSDomemSePridaKeKamaradum() {
        var village = foundVillage();
        now += 31 * 60_000;
        var view = view(joiner, HOME_SITE.offset(50, 0, 0), 0.6,
                HOME_SITE.offset(50, 0, 0), Map.of(founder, 0.7), Map.of(), Map.of());
        var action = service.checkCohesion(view);
        assertTrue(action.isPresent());
        assertEquals(SettlementService.CohesionAction.Type.JOIN_NEARBY,
                action.get().type());
        assertTrue(action.get().rebuild());
        assertEquals(village.id(), service.settlementIdOf(joiner).orElseThrow());
    }

    @Test
    void prazdnaVesniceZanika() {
        foundVillage();
        service.removeBot(founder);
        assertTrue(service.all().isEmpty());
    }

    @Test
    void vypnuteVesniceNicNedelaji() {
        var config = new BotAliveConfig.Settlement(false, 12, 8, 200, 150, 0.30, 0.60, 30, true, true, 0, 2);
        service = new SettlementService(config, null, () -> now);
        assertEquals(SettlementService.HomePlan.Kind.SOLO,
                service.planHome(plainView(founder, 0.9)).kind());
        assertTrue(service.foundSettlement(plainView(founder, 0.9), HOME_SITE,
                null, Cardinal.NORTH, 1L).isEmpty());
    }

    @Test
    void odstepenecZalozivDalArivalNevznikneVedle() {
        var village = foundVillage();
        // Moc blízko cizí vesnice založit nejde…
        assertTrue(service.foundSettlement(plainView(joiner, 0.7),
                HOME_SITE.offset(60, 0, 0), null, Cardinal.NORTH, 9L).isEmpty());
        // …dostatečně daleko ano.
        var rival = service.foundSettlement(plainView(joiner, 0.7),
                HOME_SITE.offset(200, 0, 0), null, Cardinal.NORTH, 9L);
        assertTrue(rival.isPresent());
        assertEquals(2, service.all().size());
        assertFalse(rival.get().name().equals(village.name()),
                "jména vesnic se nesmí opakovat");
    }

    @Test
    void clenVJinemSveteStaviPoSvem() {
        foundVillage();
        var elsewhere = new SocialView(founder, "Bot1", "world_nether", HOME_SITE,
                0.7, 0.5, null, Map.of(), Map.of(), Map.of());
        assertEquals(SettlementService.HomePlan.Kind.SOLO,
                service.planHome(elsewhere).kind());
    }

    @Test
    void zakladatelBezParcelySeNenabiziKZastavbe() {
        // FOUND_AT_HOME styl: zakladatel bez evidované parcely.
        var village = service.foundSettlement(plainView(founder, 0.7), HOME_SITE,
                null, Cardinal.NORTH, 42L).orElseThrow();
        assertTrue(service.claimedPlot(founder).isEmpty());
        assertEquals(1, service.suggestPlots(village.id(), 1).getFirst().index());
    }

    @Test
    void navesSePrepocitaPoZanikuZakladatelovaDomu() {
        var village = foundVillage();
        var joinerView = view(joiner, HOME_SITE, 0.7, null,
                Map.of(founder, 0.5), Map.of(), Map.of());
        assertTrue(service.join(village.id(), joinerView));
        var slot = service.suggestPlots(village.id(), 1).getFirst();
        assertTrue(service.claimPlot(village.id(), joiner, slot));
        // Zakladatelův dům zanikl (zatopený) → náves se stěhuje k domům členů.
        service.releasePlot(founder);
        var center = service.all().getFirst().center();
        assertFalse(center.equals(HOME_SITE), "náves nesmí zůstat na ruině");
        assertEquals(slot.origin().x() + 2, center.x());
        assertEquals(slot.origin().z() + 2, center.z());
    }

    @Test
    void zapornyIndexParcelySeNetombstonuje() {
        var village = foundVillage();
        service.markPlotUnusable(village.id(), -1);
        assertEquals(1, service.suggestPlots(village.id(), 1).getFirst().index(),
                "tombstone -1 nesmí ovlivnit návrhy");
    }
}
