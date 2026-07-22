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
        return new BotAliveConfig.Settlement(true, 12, 8, 200, 150, 0.30, 0.60, 30, true, true, false, false, 2, 0, 2, war());
    }

    private static BotAliveConfig.War war() {
        return new BotAliveConfig.War(false, 6.0, 1.5, 0.75, 0.5, 3, 3, 20, 4, 6, 12, true, 40.0);
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

    /**
     * Stavitel doladil výšku staveniště podle terénu – evidence to musí
     * převzít. Po restartu se ke stavbě navazuje z uloženého originu a cíle
     * si z něj dopočítávají truhlu sýpky i skladu; kdyby v evidenci zůstala
     * výška návsi, stavba by se hledala několik bloků ve vzduchu.
     */
    @Test
    void doladenaVyskaStavenisteSeVratiDoEvidence() {
        var village = villageWithFourHouses();
        var project = service.neededProject(founder).orElseThrow();
        BlockPos planned = project.origin();
        BlockPos onGround = planned.offset(0, -8, 0);

        service.updateProjectOrigin(village.id(), project.kind(), onGround);

        assertEquals(onGround, service.neededProject(founder).orElseThrow().origin(),
                "projekt zná skutečnou výšku staveniště");
        service.projectFinished(village.id(), project.kind());
        assertEquals(onGround,
                service.doneProject(village.id(), project.kind()).orElseThrow().origin(),
                "hotová stavba se eviduje tam, kde opravdu stojí");
    }

    /**
     * Nepoužitelné staveniště (sráz, jezero) projekt přestěhuje na jinou
     * parcelu. Bez toho by sídlo na jedné špatné parcele uvázlo napořád –
     * {@code neededProject} by pořád vracel týž nestavitelný projekt.
     */
    @Test
    void nepouzitelneStavenisteProjektPrestehuje() {
        var village = villageWithFourHouses();
        var project = service.neededProject(founder).orElseThrow();
        BlockPos hopeless = project.origin();

        assertTrue(service.relocateProject(village.id(), project.kind()));

        var moved = service.neededProject(founder).orElseThrow();
        assertEquals(project.kind(), moved.kind(), "pořád tatáž stavba");
        assertFalse(moved.origin().equals(hopeless), "staveniště se přesunulo");
        // Zamluvení padlo s přesunem – stavbu si bere kdokoli, včetně souseda.
        assertTrue(service.claimProject(village.id(), moved.kind(), joiner));
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
        // Po studně (a bez řemesel) si vesnice postaví společný sklad; sýpka
        // je až od 8 domů (příprava města).
        assertEquals(SettlementService.ProjectKind.WAREHOUSE,
                service.neededProject(founder).orElseThrow().kind());
    }

    @Test
    void sypkaSeNabiziAzPoStudneAOsmiDomech() {
        var village = foundVillage();
        service.houseFinished(founder);
        for (int i = 2; i <= 8; i++) {
            UUID settler = new UUID(0, i);
            var settlerView = view(settler, HOME_SITE.offset(30, 0, 0), 0.7, null,
                    Map.of(founder, 0.6), Map.of(), Map.of());
            assertTrue(service.join(village.id(), settlerView));
            var slot = service.suggestPlots(village.id(), 1).getFirst();
            assertTrue(service.claimPlot(village.id(), settler, slot));
            service.houseFinished(settler);
        }
        // 8 domů bez studny → pořád se nabízí studna, ne sýpka.
        assertEquals(SettlementService.ProjectKind.WELL,
                service.neededProject(founder).orElseThrow().kind());
        service.claimProject(village.id(), SettlementService.ProjectKind.WELL, founder);
        service.projectFinished(village.id(), SettlementService.ProjectKind.WELL);
        // Se studnou a 8 domy přichází na řadu sýpka.
        assertEquals(SettlementService.ProjectKind.GRANARY,
                service.neededProject(founder).orElseThrow().kind());
        assertTrue(service.granaryOf(village.id()).isEmpty());
        service.claimProject(village.id(), SettlementService.ProjectKind.GRANARY, founder);
        service.projectFinished(village.id(), SettlementService.ProjectKind.GRANARY);
        assertTrue(service.granaryOf(village.id()).isPresent(),
                "hotová sýpka je dohledatelná pro normy sdílení");
        // Po sýpce přichází tržiště – druhá půlka městské infrastruktury.
        assertEquals(SettlementService.ProjectKind.MARKET_STALL,
                service.neededProject(founder).orElseThrow().kind());
        service.claimProject(village.id(), SettlementService.ProjectKind.MARKET_STALL, founder);
        service.projectFinished(village.id(), SettlementService.ProjectKind.MARKET_STALL);
        assertTrue(service.doneProject(village.id(),
                        SettlementService.ProjectKind.MARKET_STALL).isPresent(),
                "hotové tržiště je dohledatelné jako kotva prodejních nabídek (SellGoal)");
        // Sýpka + tržiště + 8 domů + studna = město (víc pro stupeň netřeba).
        assertEquals(SettlementTier.MESTO,
                service.settlementOf(founder).orElseThrow().tier(),
                "tržiště po sýpce dělá z vesnice město");
        // Poslední společnou stavbou zázemí je sklad na materiál.
        assertEquals(SettlementService.ProjectKind.WAREHOUSE,
                service.neededProject(founder).orElseThrow().kind());
        service.claimProject(village.id(), SettlementService.ProjectKind.WAREHOUSE, founder);
        service.projectFinished(village.id(), SettlementService.ProjectKind.WAREHOUSE);
        // Až po skladu se městu nabídne prestižní radnice.
        assertEquals(SettlementService.ProjectKind.TOWN_HALL,
                service.neededProject(founder).orElseThrow().kind());
        service.claimProject(village.id(), SettlementService.ProjectKind.TOWN_HALL, founder);
        service.projectFinished(village.id(), SettlementService.ProjectKind.TOWN_HALL);
        // Radnice je prestižní – stupeň zůstává město, neposouvá ho.
        assertEquals(SettlementTier.MESTO,
                service.settlementOf(founder).orElseThrow().tier(),
                "radnice neposouvá stupeň");
        // Po radnici se nabídne druhá prestižní stavba – kostel.
        assertEquals(SettlementService.ProjectKind.CHURCH,
                service.neededProject(founder).orElseThrow().kind());
        service.claimProject(village.id(), SettlementService.ProjectKind.CHURCH, founder);
        service.projectFinished(village.id(), SettlementService.ProjectKind.CHURCH);
        // Ani kostel neposouvá stupeň – pořád město.
        assertEquals(SettlementTier.MESTO,
                service.settlementOf(founder).orElseThrow().tier(),
                "kostel neposouvá stupeň");
        assertTrue(service.neededProject(founder).isEmpty(),
                "s radnicí i kostelem už město nic dalšího nepotřebuje");
    }

    /** Postaví město s 8 dostavěnými domy (zakladatel + 7 osadníků). */
    private long townWithEightHouses() {
        var village = foundVillage();
        service.houseFinished(founder);
        for (int i = 2; i <= 8; i++) {
            UUID settler = new UUID(0, i);
            var settlerView = view(settler, HOME_SITE.offset(30, 0, 0), 0.7, null,
                    Map.of(founder, 0.6), Map.of(), Map.of());
            assertTrue(service.join(village.id(), settlerView));
            var slot = service.suggestPlots(village.id(), 1).getFirst();
            assertTrue(service.claimPlot(village.id(), settler, slot));
            service.houseFinished(settler);
        }
        return village.id();
    }

    /** Postaví studnu sídla (osada → vesnice). */
    private void buildWell(long settlementId) {
        assertTrue(service.neededProject(founder).isPresent()); // založí projekt studny
        assertTrue(service.claimProject(settlementId,
                SettlementService.ProjectKind.WELL, founder));
        service.projectFinished(settlementId, SettlementService.ProjectKind.WELL);
    }

    // ------------------------------------------------------ účelné dílny

    @Test
    void ucelnaDilnaSeNabiziPodleRemeslaAzPoStudni() {
        var village = villageWithFourHouses();
        long id = village.id();
        java.util.function.Function<UUID, dev.botalive.api.role.BotRole> roles =
                botId -> botId.equals(founder)
                        ? dev.botalive.api.role.BotRole.BLACKSMITH
                        : dev.botalive.api.role.BotRole.NONE;

        // Před studnou má přednost infrastruktura – nabízí se studna, ne kovárna.
        assertEquals(Optional.of(SettlementService.ProjectKind.WELL),
                service.nextProject(id, roles));
        buildWell(id);

        // Vesnice se 4 domy + kovář → kovárna (sýpka je až od 8 domů).
        assertEquals(Optional.of(SettlementService.ProjectKind.FORGE),
                service.nextProject(id, roles));

        // Když řemeslo nikdo nedělá, žádná dílna se nestaví (poptávkově) –
        // zbývá jen společný sklad na materiál.
        assertEquals(Optional.of(SettlementService.ProjectKind.WAREHOUSE),
                service.nextProject(id, botId -> dev.botalive.api.role.BotRole.NONE));
    }

    @Test
    void dilnySeNabizejiVPevnemPoradiPriority() {
        var village = villageWithFourHouses();
        long id = village.id();
        buildWell(id);
        // Kuchař i alchymista → dřív kuchyně (FORGE/KITCHEN mají přednost před ALCHEMY_LAB).
        java.util.function.Function<UUID, dev.botalive.api.role.BotRole> roles = botId -> {
            if (botId.equals(founder)) {
                return dev.botalive.api.role.BotRole.ALCHEMIST;
            }
            if (botId.equals(joiner)) {
                return dev.botalive.api.role.BotRole.COOK;
            }
            return dev.botalive.api.role.BotRole.NONE;
        };
        assertEquals(Optional.of(SettlementService.ProjectKind.KITCHEN),
                service.nextProject(id, roles));
    }

    @Test
    void mestoStaviInfrastrukturuPredDilnami() {
        long id = townWithEightHouses();
        buildWell(id);
        // 8 domů + studna + kovář: sýpka (městská infrastruktura) má přednost
        // před kovárnou – stupeň města je důležitější než řemeslná dílna.
        java.util.function.Function<UUID, dev.botalive.api.role.BotRole> smith =
                botId -> botId.equals(founder)
                        ? dev.botalive.api.role.BotRole.BLACKSMITH
                        : dev.botalive.api.role.BotRole.NONE;
        assertEquals(Optional.of(SettlementService.ProjectKind.GRANARY),
                service.nextProject(id, smith));
    }

    @Test
    void skladSeNabiziAzPoDilnachAJeDohledatelny() {
        var village = villageWithFourHouses();
        long id = village.id();
        buildWell(id);
        // S kovářem má přednost kovárna (dílna) před skladem.
        java.util.function.Function<UUID, dev.botalive.api.role.BotRole> smith =
                b -> b.equals(founder)
                        ? dev.botalive.api.role.BotRole.BLACKSMITH
                        : dev.botalive.api.role.BotRole.NONE;
        assertEquals(Optional.of(SettlementService.ProjectKind.FORGE),
                service.nextProject(id, smith));
        // Bez řemesel rovnou sklad.
        assertEquals(Optional.of(SettlementService.ProjectKind.WAREHOUSE),
                service.nextProject(id, b -> dev.botalive.api.role.BotRole.NONE));
        // Dokončený sklad je dohledatelný (origin+facing pro pozici truhly).
        var warehouse = service.neededProject(founder).orElseThrow();
        assertEquals(SettlementService.ProjectKind.WAREHOUSE, warehouse.kind());
        service.claimProject(id, SettlementService.ProjectKind.WAREHOUSE, founder);
        service.projectFinished(id, SettlementService.ProjectKind.WAREHOUSE);
        var done = service.doneProject(id, SettlementService.ProjectKind.WAREHOUSE);
        assertTrue(done.isPresent(), "hotový sklad musí být dohledatelný pro StashGoal");
        assertEquals(warehouse.origin(), done.orElseThrow().origin());
        // Nehotový projekt doneProject nevrací.
        assertTrue(service.doneProject(id, SettlementService.ProjectKind.GRANARY).isEmpty());
    }

    @Test
    void starostaJeNejzakotvenejsiClen() {
        var village = foundVillage();
        var joinView = view(joiner, HOME_SITE.offset(30, 0, 0), 0.7, null,
                Map.of(founder, 0.6), Map.of(), Map.of());
        assertTrue(service.join(village.id(), joinView));
        // Bez dat o vazbách je starostou zakladatel.
        assertEquals(founder, service.settlementOf(founder).orElseThrow().mayor());
        // Zakotvenost ze sousedských úvah: joiner má silnější vazby.
        service.noteTies(view(founder, HOME_SITE, 0.7, HOME_SITE,
                Map.of(joiner, 0.3), Map.of(), Map.of()));
        service.noteTies(view(joiner, HOME_SITE, 0.7, null,
                Map.of(founder, 0.8), Map.of(), Map.of()));
        assertEquals(joiner, service.settlementOf(founder).orElseThrow().mayor(),
                "starostou je člen s nejsilnějšími vazbami na sousedy");
    }

    @Test
    void chybejiciRemesloSeNabiziVPoradiNalehavosti() {
        var village = foundVillage();
        // Nikdo nic nedělá → nejnaléhavější je farmář.
        assertEquals(Optional.of(dev.botalive.api.role.BotRole.FARMER),
                service.missingCoreRole(village.id(),
                        id -> dev.botalive.api.role.BotRole.NONE));
        // Farmář pokrytý zakladatelem → další v řadě je lovec.
        assertEquals(Optional.of(dev.botalive.api.role.BotRole.HUNTER),
                service.missingCoreRole(village.id(),
                        id -> id.equals(founder)
                                ? dev.botalive.api.role.BotRole.FARMER
                                : dev.botalive.api.role.BotRole.NONE));
    }

    @Test
    void pokryteSidloRemesloNenabizi() {
        var village = villageWithFourHouses();
        Map<UUID, dev.botalive.api.role.BotRole> roles = Map.of(
                founder, dev.botalive.api.role.BotRole.FARMER,
                joiner, dev.botalive.api.role.BotRole.HUNTER,
                third, dev.botalive.api.role.BotRole.BLACKSMITH,
                new UUID(0, 4), dev.botalive.api.role.BotRole.BUILDER);
        assertEquals(Optional.empty(),
                service.missingCoreRole(village.id(),
                        id -> roles.getOrDefault(id, dev.botalive.api.role.BotRole.NONE)));
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
        var config = new BotAliveConfig.Settlement(true, 12, 2, 200, 150, 0.30, 0.60, 30, true, true, false, false, 2, 0, 2, war());
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
        var config = new BotAliveConfig.Settlement(false, 12, 8, 200, 150, 0.30, 0.60, 30, true, true, false, false, 2, 0, 2, war());
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

    // -------------------------------------------------------------- hradby

    @Test
    void hradbyZamluviPrvniStavitel() {
        long id = foundVillage().id();
        assertTrue(service.wallsDue(id, 60_000L, founder), "volné hradby jsou k mání");
        assertTrue(service.claimWalls(id, founder));
        assertFalse(service.wallsDue(id, 60_000L, joiner), "zabral je někdo jiný");
        assertTrue(service.wallsDue(id, 60_000L, founder), "vlastní zamluvení nepřekáží");
        assertFalse(service.claimWalls(id, joiner), "první bere");
        service.releaseWalls(id, founder);
        assertTrue(service.claimWalls(id, joiner), "po uvolnění zase volné");
    }

    @Test
    void hradbyMajiOdstupMeziSeancemi() {
        long id = foundVillage().id();
        service.wallsBuilt(id);
        assertFalse(service.wallsDue(id, 60_000L, founder), "hned po seanci se nestaví");
        now += 61_000L;
        assertTrue(service.wallsDue(id, 60_000L, founder), "po odstupu zase ano");
    }

    @Test
    void zamluveniHradebExpiruje() {
        long id = foundVillage().id();
        assertTrue(service.claimWalls(id, founder));
        assertFalse(service.claimWalls(id, joiner), "founder je drží");
        now += 11 * 60_000L; // po TTL zamluvení
        assertTrue(service.claimWalls(id, joiner), "stavitel zmizel – hradby jsou zase volné");
    }
}
