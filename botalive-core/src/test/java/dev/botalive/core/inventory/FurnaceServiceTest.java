package dev.botalive.core.inventory;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Palivová politika ({@link FurnaceService#isFuel}) – co bot ochotně obětuje
 * do pece. Staví na katalogu ({@link Items#isFuel}) a je sjednocená s pořadím
 * obětování paliva; test hlídá bug fix (netherové dřevo nehoří) i konzistenci
 * (mangrove/cherry prkna a klády jsou palivo, ne jen oak-rodina).
 */
class FurnaceServiceTest {

    @Test
    void bezneDrevoUhliJsouPalivo() {
        assertTrue(FurnaceService.isFuel(Material.COAL));
        assertTrue(FurnaceService.isFuel(Material.CHARCOAL));
        assertTrue(FurnaceService.isFuel(Material.COAL_BLOCK));
        assertTrue(FurnaceService.isFuel(Material.STICK));
        assertTrue(FurnaceService.isFuel(Material.OAK_PLANKS));
        assertTrue(FurnaceService.isFuel(Material.MANGROVE_PLANKS), "i mimo oak-rodinu");
        assertTrue(FurnaceService.isFuel(Material.OAK_LOG));
        assertTrue(FurnaceService.isFuel(Material.STRIPPED_SPRUCE_LOG));
    }

    @Test
    void netheroveDrevoBlazeRodLavaNejsouPalivo() {
        assertFalse(FurnaceService.isFuel(Material.CRIMSON_PLANKS), "netherové dřevo nehoří");
        assertFalse(FurnaceService.isFuel(Material.WARPED_STEM));
        assertFalse(FurnaceService.isFuel(Material.BLAZE_ROD), "surovina na vaření, nepálit");
        assertFalse(FurnaceService.isFuel(Material.LAVA_BUCKET), "cenný kýbl, nepálit");
        assertFalse(FurnaceService.isFuel(Material.IRON_INGOT));
        assertFalse(FurnaceService.isFuel(Material.STONE));
        assertFalse(FurnaceService.isFuel(null));
    }
}
