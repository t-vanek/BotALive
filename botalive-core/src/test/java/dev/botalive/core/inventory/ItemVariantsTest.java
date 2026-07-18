package dev.botalive.core.inventory;

import dev.botalive.core.bot.ServerSideView;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy variant itemů – rozpoznání typu lektvaru podle metadat snapshotu.
 */
class ItemVariantsTest {

    private static ServerSideView.Snapshot snapshot(Map<Integer, String> variants,
                                                    Object... slotMaterials) {
        Material[] bar = new Material[9];
        Material[] main = new Material[27];
        for (int i = 0; i < slotMaterials.length; i += 2) {
            int slot = (Integer) slotMaterials[i];
            Material material = (Material) slotMaterials[i + 1];
            if (slot < 9) {
                bar[slot] = material;
            } else {
                main[slot - 9] = material;
            }
        }
        return new ServerSideView.Snapshot(null, bar, new int[9], main, null, variants,
                null, new Material[4], null, 0, 20, 20, 0, false, false, false, 1000, 0);
    }

    @Test
    void shodaEfektuIgnorujeDelkuASilu() {
        assertTrue(ItemVariants.effectIs("fire_resistance", ItemVariants.FIRE_RESISTANCE));
        assertTrue(ItemVariants.effectIs("long_fire_resistance", ItemVariants.FIRE_RESISTANCE));
        assertTrue(ItemVariants.effectIs("strong_healing", ItemVariants.HEALING));
        assertFalse(ItemVariants.effectIs("water", ItemVariants.HEALING));
        assertFalse(ItemVariants.effectIs(null, ItemVariants.HEALING));
    }

    @Test
    void najdeLektvarVHotbaruIHlavnimInventari() {
        var inHotbar = snapshot(Map.of(3, "fire_resistance"), 3, Material.POTION);
        assertEquals(3, ItemVariants.findPotionSlot(inHotbar, ItemVariants.FIRE_RESISTANCE));

        var inMain = snapshot(Map.of(14, "long_fire_resistance"), 14, Material.POTION);
        assertEquals(14, ItemVariants.findPotionSlot(inMain, ItemVariants.FIRE_RESISTANCE));
        assertTrue(ItemVariants.hasPotion(inMain, ItemVariants.FIRE_RESISTANCE));
    }

    @Test
    void lahevVodyNeniMedicina() {
        // POTION bez varianty (voda) ani jiná varianta nesmí projít.
        var water = snapshot(Map.of(2, "water"), 2, Material.POTION);
        assertEquals(-1, ItemVariants.findPotionSlot(water, ItemVariants.HEALING));
        var noVariants = snapshot(null, 2, Material.POTION);
        assertEquals(-1, ItemVariants.findPotionSlot(noVariants, ItemVariants.HEALING));
    }

    @Test
    void splashLektvarSeHaziNePije() {
        var splash = snapshot(Map.of(1, "healing"), 1, Material.SPLASH_POTION);
        assertEquals(-1, ItemVariants.findPotionSlot(splash, ItemVariants.HEALING),
                "pitelné hledání splash nevrací");
        assertEquals(1, ItemVariants.findSplashSlot(splash, ItemVariants.HEALING),
                "vrhací hledání ho najde – hází se pod nohy");
        // A naopak: pitelná láhev není na házení.
        var drinkable = snapshot(Map.of(2, "healing"), 2, Material.POTION);
        assertEquals(-1, ItemVariants.findSplashSlot(drinkable, ItemVariants.HEALING));
    }
}
