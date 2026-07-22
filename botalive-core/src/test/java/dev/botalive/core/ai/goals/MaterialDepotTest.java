package dev.botalive.core.ai.goals;

import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.settlement.SocialView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sklad materiálu = zásobovací truhla sídla. {@link MaterialDepot#chest}
 * mapuje člena sídla na dvojtruhlu skladu ({@code WAREHOUSE}), kam sběrač
 * ukládá bloky a odkud si stavitel bere. Dokud sídlo sklad nemá, řetězec
 * je vypnutý (prázdno) a stavitel se zásobuje sám.
 */
class MaterialDepotTest {

    private static final String WORLD = "world";
    private static final BlockPos HOME_SITE = new BlockPos(100, 64, 100);

    private SettlementService service;

    private final UUID founder = new UUID(0, 1);

    @BeforeEach
    void setUp() {
        service = new SettlementService(config(), null);
    }

    private static BotAliveConfig.Settlement config() {
        return new BotAliveConfig.Settlement(true, 12, 8, 200, 150, 0.30, 0.60, 30,
                true, true, false, false, 2, 0, 2, war());
    }

    private static BotAliveConfig.War war() {
        return new BotAliveConfig.War(false, 6.0, 1.5, 0.75, 0.5, 3, 3, 20, 4, 6, 12, true, 40.0);
    }

    private SocialView view(UUID botId, double sociability) {
        return new SocialView(botId, "Bot" + botId.getLeastSignificantBits(), WORLD,
                HOME_SITE, sociability, 0.5, null, Map.of(), Map.of(), Map.of());
    }

    /** Založí vesnici, dá jí 4 domy a postaví studnu – teď se nabízí sklad. */
    private SettlementService.SettlementInfo villageReadyForWarehouse() {
        var info = service.foundSettlement(view(founder, 0.7), HOME_SITE,
                HOME_SITE.offset(-2, 0, -2), Cardinal.NORTH, 42L).orElseThrow();
        service.houseFinished(founder);
        for (int i = 2; i <= 4; i++) {
            UUID settler = new UUID(0, i);
            var settlerView = new SocialView(settler, "Bot" + i, WORLD,
                    HOME_SITE.offset(30, 0, 0), 0.7, 0.5, null,
                    Map.of(founder, 0.6), Map.of(), Map.of());
            assertTrue(service.join(info.id(), settlerView));
            var slot = service.suggestPlots(info.id(), 1).getFirst();
            assertTrue(service.claimPlot(info.id(), settler, slot));
            service.houseFinished(settler);
        }
        // Studna: osada → vesnice, odemkne nabídku skladu.
        var well = service.neededProject(founder).orElseThrow();
        assertEquals(SettlementService.ProjectKind.WELL, well.kind());
        service.claimProject(info.id(), well.kind(), founder);
        service.projectFinished(info.id(), well.kind());
        return info;
    }

    @Test
    void chestPrazdnyBezSkladu() {
        villageReadyForWarehouse();
        // Sklad ještě nestojí → zásobovací řetězec vypnutý.
        assertTrue(MaterialDepot.chest(service, founder).isEmpty(),
                "bez skladu není kam ukládat materiál");
    }

    @Test
    void chestSediNaDvojtruhleSkladu() {
        var info = villageReadyForWarehouse();
        var warehouse = service.neededProject(founder).orElseThrow();
        assertEquals(SettlementService.ProjectKind.WAREHOUSE, warehouse.kind());
        service.claimProject(info.id(), warehouse.kind(), founder);
        service.projectFinished(info.id(), warehouse.kind());

        Optional<BlockPos> chest = MaterialDepot.chest(service, founder);
        assertTrue(chest.isPresent(), "hotový sklad má zásobovací truhlu");
        assertEquals(HouseBlueprint.bedSpot(warehouse.origin(), warehouse.facing()),
                chest.get(), "truhla sedí na bedSpot skladu (parita s GranaryGoal)");
    }

    @Test
    void chestPrazdnyProNeclena() {
        villageReadyForWarehouse();
        assertTrue(MaterialDepot.chest(service, new UUID(0, 99)).isEmpty(),
                "nečlen nemá sídlo → žádný sklad");
    }
}
