package dev.botalive.core.build.plan;

import dev.botalive.core.settlement.SettlementTier;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Návrh domu: geometrie z parametrů, paleta podle dřeva, stabilní klíč. */
class HouseDesignerTest {

    @Test
    void designExposesBlueprintPaletteAndKey() {
        var design = new HouseDesigner.HouseDesign(5, 3, Material.SPRUCE_PLANKS, 42);
        assertTrue(design.blueprint() instanceof HouseGenerator, "geometrie je generátor");
        assertTrue(design.blueprint().blocksNeeded() > 0);
        assertEquals(Optional.of(Material.SPRUCE_PLANKS),
                design.palette().intended(PaletteRole.WALL), "zeď podle dřeva");
        assertEquals("house5x3", design.key());
    }

    @Test
    void sameParamsGiveSamePalette() {
        var a = new HouseDesigner.HouseDesign(5, 3, Material.OAK_PLANKS, 7);
        var b = new HouseDesigner.HouseDesign(5, 3, Material.OAK_PLANKS, 7);
        assertEquals(a.palette(), b.palette(), "deterministické podle seedu");
    }

    /**
     * Kontrakt persistence: parametry uložené do HOME dat (BuildHouseGoal)
     * musí zrekonstruovat tentýž design při opravě (MaintainHomeGoal) – jinak
     * by se generovaný dům opravoval proti špatnému plánu.
     */
    @Test
    void sizeGrowsWithSettlementTierAndShrinksWithLaziness() {
        double diligent = 0.2;
        // Osada staví útulně, vesnice/město větší (do stropu 7).
        assertEquals(5, HouseDesigner.widthFor(SettlementTier.OSADA, diligent, 7));
        assertEquals(7, HouseDesigner.widthFor(SettlementTier.VESNICE, diligent, 7));
        assertEquals(7, HouseDesigner.widthFor(SettlementTier.MESTO, diligent, 7));
        // Líný bydlí skromně i ve městě.
        assertEquals(5, HouseDesigner.widthFor(SettlementTier.MESTO, 0.9, 7));
        // Strop konfigurace platí a drží lichost; bez sídla = osada.
        assertEquals(5, HouseDesigner.widthFor(SettlementTier.MESTO, diligent, 5));
        assertEquals(9, HouseDesigner.widthFor(SettlementTier.MESTO, diligent, 9));
        assertEquals(5, HouseDesigner.widthFor(null, diligent, 7));
        assertTrue(HouseDesigner.widthFor(SettlementTier.MESTO, diligent, 8) % 2 == 1,
                "půdorys je vždy lichý");
    }

    @Test
    void tierGrowsWithProsperityAndPersonality() {
        double neutral = 0.5;
        double diligent = 0.2;
        double lazy = 0.9;
        // Prosperita je hlavní hnací síla: osada srub, vesnice solidní, město honosné.
        assertEquals(BuildTier.PROVISIONAL, HouseDesigner.tierFor(SettlementTier.OSADA, neutral));
        assertEquals(BuildTier.SOLID, HouseDesigner.tierFor(SettlementTier.VESNICE, neutral));
        assertEquals(BuildTier.REFINED, HouseDesigner.tierFor(SettlementTier.MESTO, neutral));
        // Osobnost posune o stupeň (s přichycením k mezím).
        assertEquals(BuildTier.SOLID, HouseDesigner.tierFor(SettlementTier.OSADA, diligent));
        assertEquals(BuildTier.PROVISIONAL, HouseDesigner.tierFor(SettlementTier.VESNICE, lazy));
        assertEquals(BuildTier.REFINED, HouseDesigner.tierFor(SettlementTier.MESTO, diligent));
        // Samotář (bez sídla) začíná srubem.
        assertEquals(BuildTier.PROVISIONAL, HouseDesigner.tierFor(null, neutral));
    }

    @Test
    void tierRoundTripsThroughHomeData() {
        var original = new HouseDesigner.HouseDesign(5, 3, Material.OAK_PLANKS, 42,
                BuildTier.PROVISIONAL);
        // Serializace/rekonstrukce jako finishHouse ↔ resolveDesign (btier = ordinal).
        String btier = String.valueOf(original.tier().ordinal());
        var restored = new HouseDesigner.HouseDesign(5, 3, Material.OAK_PLANKS, 42,
                BuildTier.fromOrdinal(Integer.parseInt(btier)));
        assertEquals(original.palette(), restored.palette(), "stejná paleta i tier");
        // Starý dům bez uloženého stupně = SOLID (žádná regrese vzhledu).
        assertEquals(BuildTier.SOLID,
                new HouseDesigner.HouseDesign(5, 3, Material.OAK_PLANKS, 42).tier());
    }

    @Test
    void designRoundTripsThroughHomeData() {
        var original = new HouseDesigner.HouseDesign(5, 3, Material.SPRUCE_PLANKS, 123456789L);
        // Serializace jako ve finishHouse.
        String bw = String.valueOf(original.width());
        String bh = String.valueOf(original.wallHeight());
        String bwood = original.wood().name();
        String bseed = String.valueOf(original.seed());
        // Rekonstrukce jako v resolveDesign.
        var restored = new HouseDesigner.HouseDesign(Integer.parseInt(bw),
                Integer.parseInt(bh), Material.valueOf(bwood), Long.parseLong(bseed));
        assertEquals(original.palette(), restored.palette(), "stejná paleta");
        assertEquals(original.blueprint().blocksNeeded(),
                restored.blueprint().blocksNeeded(), "stejná geometrie");
        assertEquals(original.key(), restored.key());
    }
}
