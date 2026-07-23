package dev.botalive.core.inventory;

import dev.botalive.core.bot.ServerSideView;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // Bez stavebního bloku v hotbaru (klády jsou nově stavební blok, proto
        // PAPER jako neutrální výplň) padne volba na nejpočetnější neceněný stack.
        Material[] hotbar = {Material.STICK, Material.PAPER, Material.STRING,
                Material.FLINT, Material.FEATHER, Material.COAL,
                Material.IRON_INGOT, Material.DIAMOND, Material.OAK_SAPLING};
        int[] counts = {4, 22, 3, 2, 2, 3, 12, 40, 1};
        int slot = InventoryHelper.chooseHotbarDumpSlot(
                snapshot(hotbar, counts, new Material[27]));
        assertEquals(7, slot, "nejpočetnější obyčejný stack (diamanty 40)");
    }

    @Test
    void odkladaciSlotChraniLukJakoZbran() {
        // Luk se ovládá z hotbaru jako meč – nesmí se vytlačit kvůli přitaženému
        // itemu, dokud je po ruce obyčejná surovina (papír). Přes tier krumpáče
        // luk (tier 0) neprojde; katalog Items.isWeapon ho chrání spolu s kuší
        // a trojzubcem. Bez něj by luk propadl mezi „necenné" a obětoval se.
        Material[] hotbar = {Material.BOW, Material.PAPER, Material.IRON_SWORD,
                Material.IRON_PICKAXE, Material.DIAMOND_AXE, Material.STONE_SHOVEL,
                Material.BREAD, Material.COOKED_BEEF, Material.CARROT};
        int[] counts = {1, 1, 1, 1, 1, 1, 5, 2, 3};
        int slot = InventoryHelper.chooseHotbarDumpSlot(
                snapshot(hotbar, counts, new Material[27]));
        assertEquals(1, slot, "obětuje papír, ne luk (zbraň zůstává po ruce)");
    }

    @Test
    void kvalitaJidlaPorada() {
        // Pořádné jídlo (0) < syrové (1) < zlaté jablko (2) < nejídlo (3).
        assertEquals(0, InventoryHelper.foodRank(Material.COOKED_BEEF), "vařené = pořádné");
        assertEquals(0, InventoryHelper.foodRank(Material.BREAD));
        assertEquals(1, InventoryHelper.foodRank(Material.BEEF), "syrové maso");
        assertEquals(1, InventoryHelper.foodRank(Material.CHICKEN), "syrové kuře (otráví)");
        assertEquals(2, InventoryHelper.foodRank(Material.GOLDEN_APPLE), "rezerva na nouzi");
        assertEquals(2, InventoryHelper.foodRank(Material.ENCHANTED_GOLDEN_APPLE));
        assertEquals(3, InventoryHelper.foodRank(Material.COBBLESTONE), "není jídlo");
        assertEquals(3, InventoryHelper.foodRank(null));
        // Klíčové uspořádání politiky: sníst dřív pořádné, zlaté jablko nakonec.
        assertTrue(InventoryHelper.foodRank(Material.COOKED_BEEF)
                < InventoryHelper.foodRank(Material.BEEF));
        assertTrue(InventoryHelper.foodRank(Material.BEEF)
                < InventoryHelper.foodRank(Material.GOLDEN_APPLE));
        assertTrue(InventoryHelper.isReserveFood(Material.GOLDEN_APPLE));
        assertFalse(InventoryHelper.isReserveFood(Material.BREAD));
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

    @Test
    void bankovatelneJsouCennostiNeOdpad() {
        assertTrue(InventoryHelper.isBankable(Material.DIAMOND));
        assertTrue(InventoryHelper.isBankable(Material.IRON_INGOT));
        assertTrue(InventoryHelper.isBankable(Material.RAW_IRON));
        assertTrue(InventoryHelper.isBankable(Material.COAL));
        // Odpad a spotřebák se nebankuje (řeší depositJunk / nechává se).
        assertFalse(InventoryHelper.isBankable(Material.COBBLESTONE));
        assertFalse(InventoryHelper.isBankable(Material.BREAD));
        assertFalse(InventoryHelper.isBankable(Material.IRON_PICKAXE));
        // Netheritový řetězec je schválně mimo – příliš vzácný na bankování.
        assertFalse(InventoryHelper.isBankable(Material.NETHERITE_INGOT));
        assertFalse(InventoryHelper.isBankable(null));
    }

    @Test
    void bankableSurplusPocitaPrebytekNadRezervu() {
        // 40 uhlí (rezerva 32) → přebytek 8; 10 železa (rezerva 16) → 0.
        Material[] hotbar = new Material[9];
        hotbar[0] = Material.COAL;
        hotbar[1] = Material.IRON_INGOT;
        int[] counts = new int[9];
        counts[0] = 40;
        counts[1] = 10;
        assertEquals(8, InventoryHelper.bankableSurplus(
                snapshot(hotbar, counts, new Material[27])));
    }

    @Test
    void bankableSurplusSecitaNaprricInventarem() {
        // Uhlí rozdělené na hotbar (20) + hlavní inventář (20) = 40, rezerva 32
        // → přebytek 8. Rezerva se odečítá jen jednou z celku, ne z každé půlky.
        Material[] hotbar = new Material[9];
        hotbar[0] = Material.COAL;
        int[] hotbarCounts = new int[9];
        hotbarCounts[0] = 20;
        Material[] main = new Material[27];
        main[0] = Material.COAL;
        int[] mainCounts = new int[27];
        mainCounts[0] = 20;
        var snap = new ServerSideView.Snapshot(null, hotbar, hotbarCounts, main, mainCounts,
                null, null, new Material[4], null, 0, 20, 20, 0, false, false, false, 1000, 0);
        assertEquals(8, InventoryHelper.bankableSurplus(snap));
    }

    @Test
    void freeSlotsPocitaProstorBatohu() {
        Material[] hotbar = new Material[9];
        hotbar[0] = Material.STONE;
        hotbar[1] = Material.STONE;
        Material[] main = new Material[27];
        main[0] = Material.DIRT;
        // 3 zaplněné z 36 → 33 volných.
        assertEquals(33, InventoryHelper.freeSlots(snapshot(hotbar, new int[9], main)));
        assertEquals(36, InventoryHelper.freeSlots(
                snapshot(new Material[9], new int[9], new Material[27])));
        assertEquals(0, InventoryHelper.freeSlots(null));
    }
}
