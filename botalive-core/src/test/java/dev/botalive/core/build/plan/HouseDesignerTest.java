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
}
