package dev.botalive.core.crafting;

import dev.botalive.core.bot.ServerSideView.Snapshot;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy katalogu stanic dílen vyráběných na míru ({@code STATION_RECIPES}).
 *
 * <p>Kryje čistou bránu {@link CraftingService#canCraftStation} – stavitel
 * dílny vyrobí stanici mimo běžnou progresi (řezák, tkalcovský stav…) jen když
 * má v batohu suroviny. Autoritativní {@code craftStation} běží na serveru a
 * testuje se integračně; tady jde o rozhodovací logiku.</p>
 */
class CraftStationTest {

    /** Snapshot s inventářem z dvojic (materiál, počet); zbytek je prázdný. */
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
    void sipardkaChceProknyAKresadlo() {
        // Šípařská deska: 4 prkna + 2 pazourky.
        assertFalse(CraftingService.canCraftStation(inv(Material.OAK_PLANKS, 4), Material.FLETCHING_TABLE));
        assertTrue(CraftingService.canCraftStation(
                inv(Material.OAK_PLANKS, 4, Material.FLINT, 2), Material.FLETCHING_TABLE));
    }

    @Test
    void ruznaPrknaSeScitaji() {
        // Predikát bere jakékoli *_PLANKS – dub i smrk dohromady stačí.
        assertTrue(CraftingService.canCraftStation(
                inv(Material.OAK_PLANKS, 2, Material.SPRUCE_PLANKS, 2, Material.FLINT, 2),
                Material.FLETCHING_TABLE));
    }

    @Test
    void tkalcovskyStavChceMotouz() {
        // Tkalcovský stav: 2 prkna + 2 provázky (lovcova kořist z pavouků).
        assertFalse(CraftingService.canCraftStation(
                inv(Material.OAK_PLANKS, 2), Material.LOOM));
        assertTrue(CraftingService.canCraftStation(
                inv(Material.OAK_PLANKS, 2, Material.STRING, 2), Material.LOOM));
    }

    @Test
    void kartografieChcePapir() {
        assertTrue(CraftingService.canCraftStation(
                inv(Material.BIRCH_PLANKS, 4, Material.PAPER, 2), Material.CARTOGRAPHY_TABLE));
    }

    @Test
    void koželužnaChceSedmŽeleza() {
        assertFalse(CraftingService.canCraftStation(
                inv(Material.IRON_INGOT, 6), Material.CAULDRON));
        assertTrue(CraftingService.canCraftStation(
                inv(Material.IRON_INGOT, 7), Material.CAULDRON));
    }

    @Test
    void kamenictviChceKamenAŽelezo() {
        assertTrue(CraftingService.canCraftStation(
                inv(Material.STONE, 3, Material.IRON_INGOT, 1), Material.STONECUTTER));
    }

    @Test
    void bruskaChceKamennouDlazdici() {
        // Bruska: 2 klacky + 2 prkna + 1 kamenná dlaždice.
        assertFalse(CraftingService.canCraftStation(
                inv(Material.STICK, 2, Material.OAK_PLANKS, 2), Material.GRINDSTONE));
        assertTrue(CraftingService.canCraftStation(
                inv(Material.STICK, 2, Material.OAK_PLANKS, 2, Material.STONE_SLAB, 1),
                Material.GRINDSTONE));
    }

    @Test
    void kamennaDlazdiceNeniDrevena() {
        // WOOD_SLAB predikát nesmí spolknout kamennou dlaždici (pult knihovny).
        assertFalse(CraftingService.canCraftStation(
                inv(Material.STONE_SLAB, 4, Material.BOOKSHELF, 1), Material.LECTERN));
        assertTrue(CraftingService.canCraftStation(
                inv(Material.OAK_SLAB, 4, Material.BOOKSHELF, 1), Material.LECTERN));
    }

    @Test
    void stanicMimoKatalogNelzeVyrobit() {
        // Pec a ponk jdou progresí, ne na míru – katalog je nezná.
        assertFalse(CraftingService.canCraftStation(
                inv(Material.COBBLESTONE, 64), Material.FURNACE));
        assertFalse(CraftingService.canCraftStation(
                inv(Material.OAK_PLANKS, 64), Material.CRAFTING_TABLE));
    }

    @Test
    void nullSnapshotJeBezpecny() {
        assertFalse(CraftingService.canCraftStation(null, Material.LOOM));
    }

    @Test
    void katalogNesahaMimoBeznouProgresi() {
        // Stanice, které si bot obstará survival progresí, v katalogu být nesmí
        // (jinak by je stavitel vyráběl na míru dvakrát).
        for (Material progression : List.of(
                Material.FURNACE, Material.SMOKER, Material.BLAST_FURNACE,
                Material.CRAFTING_TABLE, Material.SMITHING_TABLE, Material.COMPOSTER,
                Material.ENCHANTING_TABLE, Material.BREWING_STAND)) {
            assertFalse(CraftingService.canCraftStation(inv(), progression),
                    progression + " nesmí být v katalogu stanic na míru");
        }
    }
}
