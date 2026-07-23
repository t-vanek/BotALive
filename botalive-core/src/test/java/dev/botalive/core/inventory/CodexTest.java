package dev.botalive.core.inventory;

import dev.botalive.core.inventory.Items.ItemCategory;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codex – botní databáze nad katalogy. Kategorizace, fakta, popis a
 * agregace přes celou vanillu (registry-free, testovatelné).
 */
class CodexTest {

    @Test
    void kategorizace() {
        assertEquals(ItemCategory.TOOL, Codex.categoryOf(Material.DIAMOND_PICKAXE));
        assertEquals(ItemCategory.VALUABLE, Codex.categoryOf(Material.DIAMOND));
        assertEquals(ItemCategory.BLOCK, Codex.categoryOf(Material.IRON_ORE));
        assertEquals(ItemCategory.BLOCK, Codex.categoryOf(Material.COBBLESTONE));
    }

    @Test
    void faktaRudy() {
        Codex.Facts f = Codex.facts(Material.IRON_ORE);
        assertTrue(f.ore());
        assertEquals(5.0, f.oreValue());
        assertEquals(3, f.requiredPickTier());
        assertFalse(f.buildingBlock());
    }

    @Test
    void faktaCennosti() {
        Codex.Facts d = Codex.facts(Material.DIAMOND);
        assertTrue(d.valuable());
        assertTrue(d.bankable());
        assertEquals(12, d.bankReserve());
        assertFalse(d.ore());
        assertNull(d.oreValue());

        Codex.Facts coal = Codex.facts(Material.COAL);
        assertTrue(coal.fuel());
        assertTrue(coal.bankable());
    }

    @Test
    void lidskyPopis() {
        String ore = Codex.describe(Material.IRON_ORE);
        assertTrue(ore.contains("ruda"), ore);
        assertTrue(ore.contains("tier 3"), ore);
        assertTrue(ore.contains("hodnota 5"), ore);

        String diamond = Codex.describe(Material.DIAMOND);
        assertTrue(diamond.contains("bankuje se nad 12"), diamond);

        assertEquals("nic", Codex.describe(null));
    }

    @Test
    void indexPokryvaVanillu() {
        // Nástroje i bloky se v indexu opravdu objeví a nejsou prázdné.
        assertTrue(Codex.inCategory(ItemCategory.TOOL).contains(Material.DIAMOND_PICKAXE));
        assertTrue(Codex.inCategory(ItemCategory.BLOCK).contains(Material.COBBLESTONE));
        assertTrue(Codex.count(ItemCategory.TOOL) > 0);
        assertTrue(Codex.count(ItemCategory.BLOCK) > 0);
        // Histogram sečte stovky materiálů celé vanilly.
        int total = Codex.histogram().values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(total > 500, "vanilla má stovky materiálů, index jich má " + total);
    }
}
