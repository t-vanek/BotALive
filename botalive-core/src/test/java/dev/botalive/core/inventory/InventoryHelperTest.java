package dev.botalive.core.inventory;

import dev.botalive.core.bot.ServerSideView;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy vyhledávání v hlavním inventáři a volby odkládacího slotu.
 */
class InventoryHelperTest {

    private static ServerSideView.Snapshot snapshot(Material[] hotbar, int[] counts,
                                                    Material[] main) {
        return new ServerSideView.Snapshot(null, hotbar, counts, main, null, null, null,
                new Material[4], null, 0, 20, 20, 0, false, false, false, 1000, 0);
    }

    @Test
    void najdeNejlepsiNastrojVHlavnimInventari() {
        Material[] main = new Material[27];
        main[3] = Material.STONE_PICKAXE;
        main[10] = Material.IRON_PICKAXE;
        main[20] = Material.WOODEN_PICKAXE;
        int best = InventoryHelper.findBestInMain(
                snapshot(new Material[9], new int[9], main),
                m -> InventoryHelper.isTool(m, InventoryHelper.ToolType.PICKAXE));
        assertEquals(10, best, "má vybrat železný krumpáč (nejvyšší tier)");
    }

    @Test
    void odkladaciSlotPreferujePrazdny() {
        Material[] hotbar = {Material.COBBLESTONE, null, Material.IRON_SWORD,
                null, null, null, null, null, null};
        int slot = InventoryHelper.chooseHotbarDumpSlot(
                snapshot(hotbar, new int[9], new Material[27]));
        assertEquals(1, slot, "první prázdný slot");
    }

    @Test
    void odkladaciSlotNikdyNeobetujeNastrojKdyzJeMaterial() {
        Material[] hotbar = {Material.IRON_SWORD, Material.COBBLESTONE,
                Material.IRON_PICKAXE, Material.BREAD, Material.DIAMOND_AXE,
                Material.SHIELD, Material.BOW, Material.IRON_HELMET, Material.TORCH};
        int[] counts = {1, 32, 1, 5, 1, 1, 1, 1, 8};
        int slot = InventoryHelper.chooseHotbarDumpSlot(
                snapshot(hotbar, counts, new Material[27]));
        assertEquals(1, slot, "obětuje se stack cobble, ne nástroj/jídlo");
    }

    @Test
    void odkladaciSlotFallbackNejpocetnejsiNeceny() {
        Material[] hotbar = {Material.STICK, Material.OAK_LOG, Material.STRING,
                Material.FLINT, Material.FEATHER, Material.COAL,
                Material.IRON_INGOT, Material.DIAMOND, Material.OAK_SAPLING};
        int[] counts = {4, 22, 3, 2, 2, 3, 12, 40, 1};
        int slot = InventoryHelper.chooseHotbarDumpSlot(
                snapshot(hotbar, counts, new Material[27]));
        assertEquals(7, slot, "nejpočetnější obyčejný stack (diamanty 40)");
    }

    @Test
    void prazdnyHlavniInventarVraciMinusJedna() {
        assertEquals(-1, InventoryHelper.findBestInMain(
                snapshot(new Material[9], new int[9], new Material[27]),
                m -> true));
        assertTrue(InventoryHelper.findBestInMain(null, m -> true) < 0);
    }

    @Test
    void countEstimatePocitaHlavniInventarPresne() {
        // Stack 14 obsidiánu v JEDNOM slotu hlavního inventáře musí dát 14 –
        // paušál 4/slot by práh rámu portálu (14) učinil nedosažitelným.
        Material[] main = new Material[27];
        int[] mainCounts = new int[27];
        main[5] = Material.OBSIDIAN;
        mainCounts[5] = 14;
        var withCounts = new ServerSideView.Snapshot(null, new Material[9], new int[9],
                main, mainCounts, null, null, new Material[4], null,
                0, 20, 20, 0, false, false, false, 1000, 0);
        assertEquals(14, InventoryHelper.countEstimate(withCounts,
                m -> m == Material.OBSIDIAN));

        // Bez počtů (ručně sestavený snapshot) zůstává konzervativní odhad.
        var withoutCounts = snapshot(new Material[9], new int[9], main);
        assertEquals(4, InventoryHelper.countEstimate(withoutCounts,
                m -> m == Material.OBSIDIAN));
    }
}
