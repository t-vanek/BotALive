package dev.botalive.core.crafting;

import dev.botalive.core.bot.ServerSideView.Snapshot;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy výroby plotu z prken ({@link CraftingService#canCraftFencing} a
 * {@link CraftingService#dominantPlanks}) – čistá brána pro cíl plotu; recept:
 * plaňka 4 prkna + 2 klacky → 3 ks, branka 2 prkna + 4 klacky → 1, klacky 2
 * prkna → 4.
 */
class FencingCraftTest {

    /** Snapshot s inventářem z dvojic (materiál, počet); zbytek prázdný. */
    private static Snapshot inv(Object... pairs) {
        Material[] main = new Material[pairs.length / 2];
        int[] counts = new int[pairs.length / 2];
        for (int i = 0; i < pairs.length; i += 2) {
            main[i / 2] = (Material) pairs[i];
            counts[i / 2] = (Integer) pairs[i + 1];
        }
        return new Snapshot(
                null, new Material[0], new int[0], main, counts,
                Map.of(), null, new Material[0], null, 0,
                20.0, 20, 0, false, false, false, 0L, 0L);
    }

    @Test
    void plotZKlackuVBatohu() {
        // 3 plaňky = 1 šarže = 4 prkna + 2 klacky; klacky bot má.
        assertTrue(CraftingService.canCraftFencing(
                inv(Material.OAK_PLANKS, 4, Material.STICK, 2), Material.OAK_PLANKS, 3, 0));
        // O prkno míň nestačí.
        assertFalse(CraftingService.canCraftFencing(
                inv(Material.OAK_PLANKS, 3, Material.STICK, 2), Material.OAK_PLANKS, 3, 0));
    }

    @Test
    void klackySeDomackneZPrken() {
        // Bez klacků: 1 šarže plaňků = 4 prkna + 2 klacky; klacky = 2 prkna → 4.
        // Potřeba 4 + 2 = 6 prken (2 klacky se dorobí z 2 prken, přebytek 2 klacky).
        assertTrue(CraftingService.canCraftFencing(
                inv(Material.OAK_PLANKS, 6), Material.OAK_PLANKS, 3, 0));
        assertFalse(CraftingService.canCraftFencing(
                inv(Material.OAK_PLANKS, 5), Material.OAK_PLANKS, 3, 0));
    }

    @Test
    void brankaChcePrknaAKlacky() {
        // Branka: 2 prkna + 4 klacky; klacky z prken (4 klacky = 2 prkna).
        // Celkem 2 + 2 = 4 prkna.
        assertTrue(CraftingService.canCraftFencing(
                inv(Material.OAK_PLANKS, 4), Material.OAK_PLANKS, 0, 1));
        assertFalse(CraftingService.canCraftFencing(
                inv(Material.OAK_PLANKS, 3), Material.OAK_PLANKS, 0, 1));
    }

    @Test
    void plotIBrankaSdilejiZasobuPrken() {
        // 20 plaňků = 7 šarží (21 ks) = 28 prken + 14 klacků; 1 branka = 2 prkna
        // + 4 klacky. Klacky (18) z prken: 5 šarží → 20 klacků = 10 prken.
        // Prkna: 28 + 2 + 10 = 40.
        assertTrue(CraftingService.canCraftFencing(
                inv(Material.OAK_PLANKS, 40), Material.OAK_PLANKS, 20, 1));
        assertFalse(CraftingService.canCraftFencing(
                inv(Material.OAK_PLANKS, 39), Material.OAK_PLANKS, 20, 1));
    }

    @Test
    void jinyDruhPrkenSeNepocita() {
        // Recept chce jeden druh – smrk se na dubový plot nepočítá.
        assertFalse(CraftingService.canCraftFencing(
                inv(Material.SPRUCE_PLANKS, 40), Material.OAK_PLANKS, 20, 1));
    }

    @Test
    void nullBezpecny() {
        assertFalse(CraftingService.canCraftFencing(null, Material.OAK_PLANKS, 3, 0));
        assertFalse(CraftingService.canCraftFencing(inv(Material.OAK_PLANKS, 40), null, 3, 0));
    }

    @Test
    void dominantniPrknaVyberouNejcetnejsi() {
        assertEquals(Material.SPRUCE_PLANKS, CraftingService.dominantPlanks(
                inv(Material.OAK_PLANKS, 4, Material.SPRUCE_PLANKS, 12)));
        assertNull(CraftingService.dominantPlanks(inv(Material.COBBLESTONE, 64)));
        assertNull(CraftingService.dominantPlanks(null));
    }

    @Test
    void kladySePrevedouNaSpravnaPrkna() {
        assertEquals(Material.OAK_PLANKS, CraftingService.planksFromLog(Material.OAK_LOG));
        assertEquals(Material.SPRUCE_PLANKS, CraftingService.planksFromLog(Material.SPRUCE_LOG));
        assertEquals(Material.BIRCH_PLANKS, CraftingService.planksFromLog(Material.STRIPPED_BIRCH_LOG));
        assertEquals(Material.OAK_PLANKS, CraftingService.planksFromLog(Material.OAK_WOOD));
    }
}
