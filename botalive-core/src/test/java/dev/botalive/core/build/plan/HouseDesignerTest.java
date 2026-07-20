package dev.botalive.core.build.plan;

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
