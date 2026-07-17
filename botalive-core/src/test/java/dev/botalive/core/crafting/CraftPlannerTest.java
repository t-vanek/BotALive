package dev.botalive.core.crafting;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy kompletní survival crafting progrese (sdílený plánovač).
 */
class CraftPlannerTest {

    /** Sestaví stav z dvojic (materiál, počet). */
    private static CraftPlanner.State state(Object... pairs) {
        Map<Material, Integer> items = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            items.merge((Material) pairs[i], (Integer) pairs[i + 1], Integer::sum);
        }
        Material log = items.keySet().stream()
                .filter(m -> m.name().endsWith("_LOG")).findFirst().orElse(null);
        Material plank = items.keySet().stream()
                .filter(m -> m.name().endsWith("_PLANKS")).findFirst().orElse(null);
        Material wool = items.keySet().stream()
                .filter(m -> m.name().endsWith("_WOOL")).findFirst().orElse(null);
        return new CraftPlanner.State(items, log, plank, Material.COBBLESTONE, wool);
    }

    /** Kompletně vybavený bot (nic dalšího nedává smysl). */
    private static CraftPlanner.State fullKit() {
        return state(Material.OAK_PLANKS, 4, Material.STICK, 4, Material.COBBLESTONE, 3,
                Material.CRAFTING_TABLE, 1, Material.FURNACE, 1, Material.TORCH, 8,
                Material.DIAMOND_PICKAXE, 1, Material.DIAMOND_SWORD, 1,
                Material.DIAMOND_AXE, 1, Material.STONE_SHOVEL, 1, Material.SHIELD, 1,
                Material.DIAMOND_CHESTPLATE, 1, Material.DIAMOND_LEGGINGS, 1,
                Material.DIAMOND_HELMET, 1, Material.DIAMOND_BOOTS, 1,
                Material.BOW, 1, Material.ARROW, 16, Material.CHEST, 1,
                Material.OAK_BOAT, 1, Material.OAK_DOOR, 1, Material.RED_BED, 1);
    }

    @Test
    void bezSurovinNicNeplanuje() {
        assertNull(CraftPlanner.next(state()));
    }

    @Test
    void progresePrknaTyckyPonkNastroje() {
        assertEquals("prkna", CraftPlanner.next(state(Material.OAK_LOG, 3)).id());
        assertEquals("tyčky", CraftPlanner.next(state(Material.OAK_PLANKS, 4)).id());
        assertEquals("ponk", CraftPlanner.next(
                state(Material.OAK_PLANKS, 5, Material.STICK, 4)).id());
        assertEquals("dřevěný krumpáč", CraftPlanner.next(state(
                Material.OAK_PLANKS, 5, Material.STICK, 4,
                Material.CRAFTING_TABLE, 1)).id());
    }

    @Test
    void kamennaGeneraceVcetneSekeryALopaty() {
        CraftPlanner.State s = state(Material.OAK_PLANKS, 5, Material.STICK, 8,
                Material.COBBLESTONE, 12, Material.CRAFTING_TABLE, 1,
                Material.WOODEN_PICKAXE, 1, Material.WOODEN_SWORD, 1,
                Material.WOODEN_AXE, 1);
        assertEquals("kamenný krumpáč", CraftPlanner.next(s).id());
        CraftPlanner.State s2 = state(Material.OAK_PLANKS, 5, Material.STICK, 8,
                Material.COBBLESTONE, 12, Material.CRAFTING_TABLE, 1,
                Material.STONE_PICKAXE, 1, Material.WOODEN_SWORD, 1,
                Material.WOODEN_AXE, 1);
        assertEquals("kamenný meč", CraftPlanner.next(s2).id());
        CraftPlanner.State s3 = state(Material.OAK_PLANKS, 5, Material.STICK, 8,
                Material.COBBLESTONE, 12, Material.CRAFTING_TABLE, 1,
                Material.STONE_PICKAXE, 1, Material.STONE_SWORD, 1,
                Material.WOODEN_AXE, 1);
        assertEquals("kamenná sekera", CraftPlanner.next(s3).id());
        CraftPlanner.State s4 = state(Material.OAK_PLANKS, 5, Material.STICK, 8,
                Material.COBBLESTONE, 12, Material.CRAFTING_TABLE, 1,
                Material.STONE_PICKAXE, 1, Material.STONE_SWORD, 1,
                Material.STONE_AXE, 1);
        assertEquals("kamenná lopata", CraftPlanner.next(s4).id());
    }

    @Test
    void pecPredZelezem() {
        CraftPlanner.State s = state(Material.OAK_PLANKS, 5, Material.STICK, 8,
                Material.COBBLESTONE, 12, Material.CRAFTING_TABLE, 1,
                Material.STONE_PICKAXE, 1, Material.STONE_SWORD, 1,
                Material.STONE_AXE, 1, Material.STONE_SHOVEL, 1);
        CraftPlanner.Plan pec = CraftPlanner.next(s);
        assertEquals("pec", pec.id());
        assertEquals(8, pec.ingredients().get(Material.COBBLESTONE));
    }

    @Test
    void zeleznaGeneraceStitABrneni() {
        // Základ: kamenná výbava + pec + pochodně, 20 ingotů.
        Object[] base = {Material.OAK_PLANKS, 12, Material.STICK, 8,
                Material.COBBLESTONE, 3, Material.CRAFTING_TABLE, 1,
                Material.FURNACE, 1, Material.TORCH, 8,
                Material.STONE_SWORD, 1, Material.STONE_AXE, 1,
                Material.STONE_SHOVEL, 1, Material.IRON_INGOT, 20};
        CraftPlanner.State s = state(concat(base, Material.STONE_PICKAXE, 1));
        assertEquals("železný krumpáč", CraftPlanner.next(s).id());

        CraftPlanner.State s2 = state(concat(base, Material.IRON_PICKAXE, 1));
        assertEquals("železný meč", CraftPlanner.next(s2).id());

        CraftPlanner.State s3 = state(concat(base, Material.IRON_PICKAXE, 1,
                Material.IRON_SWORD, 1, Material.IRON_AXE, 1));
        assertEquals("štít", CraftPlanner.next(s3).id());

        CraftPlanner.State s4 = state(concat(base, Material.IRON_PICKAXE, 1,
                Material.IRON_SWORD, 1, Material.IRON_AXE, 1, Material.SHIELD, 1));
        assertEquals("železný prsní plát", CraftPlanner.next(s4).id());

        CraftPlanner.State s5 = state(concat(base, Material.IRON_PICKAXE, 1,
                Material.IRON_SWORD, 1, Material.IRON_AXE, 1, Material.SHIELD, 1,
                Material.IRON_CHESTPLATE, 1));
        assertEquals("železné kalhoty", CraftPlanner.next(s5).id());
    }

    @Test
    void diamantovaGenerace() {
        CraftPlanner.State s = state(Material.OAK_PLANKS, 12, Material.STICK, 8,
                Material.COBBLESTONE, 3, Material.CRAFTING_TABLE, 1,
                Material.FURNACE, 1, Material.TORCH, 8, Material.SHIELD, 1,
                Material.IRON_PICKAXE, 1, Material.IRON_SWORD, 1, Material.IRON_AXE, 1,
                Material.STONE_SHOVEL, 1,
                Material.IRON_CHESTPLATE, 1, Material.IRON_LEGGINGS, 1,
                Material.IRON_HELMET, 1, Material.IRON_BOOTS, 1,
                Material.DIAMOND, 24);
        assertEquals("diamantový krumpáč", CraftPlanner.next(s).id());
    }

    @Test
    void lukSipyTruhlaLodka() {
        Object[] geared = {Material.OAK_PLANKS, 20, Material.STICK, 8,
                Material.COBBLESTONE, 3, Material.CRAFTING_TABLE, 1,
                Material.FURNACE, 1, Material.TORCH, 8, Material.SHIELD, 1,
                Material.DIAMOND_PICKAXE, 1, Material.DIAMOND_SWORD, 1,
                Material.DIAMOND_AXE, 1, Material.STONE_SHOVEL, 1,
                Material.DIAMOND_CHESTPLATE, 1, Material.DIAMOND_LEGGINGS, 1,
                Material.DIAMOND_HELMET, 1, Material.DIAMOND_BOOTS, 1};
        CraftPlanner.State bow = state(concat(geared, Material.STRING, 3));
        assertEquals("luk", CraftPlanner.next(bow).id());

        CraftPlanner.State arrows = state(concat(geared, Material.BOW, 1,
                Material.FLINT, 2, Material.FEATHER, 2));
        assertEquals("šípy", CraftPlanner.next(arrows).id());

        CraftPlanner.State chest = state(concat(geared, Material.BOW, 1,
                Material.ARROW, 16));
        assertEquals("truhla", CraftPlanner.next(chest).id());

        CraftPlanner.State boat = state(concat(geared, Material.BOW, 1,
                Material.ARROW, 16, Material.CHEST, 1));
        assertEquals("loďka", CraftPlanner.next(boat).id());
    }

    @Test
    void kompletniVybavaNicNepotrebuje() {
        assertNull(CraftPlanner.next(fullKit()));
    }

    @Test
    void pochodneZUhliATycek() {
        CraftPlanner.State s = state(Material.OAK_PLANKS, 5, Material.STICK, 8,
                Material.COBBLESTONE, 3, Material.CRAFTING_TABLE, 1,
                Material.FURNACE, 1, Material.STONE_PICKAXE, 1,
                Material.STONE_SWORD, 1, Material.STONE_AXE, 1,
                Material.STONE_SHOVEL, 1, Material.COAL, 3);
        CraftPlanner.Plan torch = CraftPlanner.next(s);
        assertEquals("pochodně", torch.id());
        assertFalse(torch.needsTable());
    }

    @Test
    void vsechnyPlanyMajiValidniMatice() {
        // Sanity: každý plán z progrese má neprázdnou matici a ingredience.
        CraftPlanner.Plan plan = CraftPlanner.next(state(Material.OAK_LOG, 3));
        assertTrue(plan.ingredients().values().stream().allMatch(v -> v > 0));
        assertEquals(9, plan.matrix().length);
    }

    private static Object[] concat(Object[] base, Object... extra) {
        Object[] out = new Object[base.length + extra.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(extra, 0, out, base.length, extra.length);
        return out;
    }
}
