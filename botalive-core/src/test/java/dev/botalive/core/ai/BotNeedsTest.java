package dev.botalive.core.ai;

import dev.botalive.core.bot.ServerSideView;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy potřeb bota – wishlist těžby a tier gating.
 */
class BotNeedsTest {

    private static ServerSideView.Snapshot snapshot(Material... hotbar) {
        Material[] bar = new Material[9];
        int[] counts = new int[9];
        for (int i = 0; i < hotbar.length && i < 9; i++) {
            bar[i] = hotbar[i];
            counts[i] = hotbar[i] == null ? 0 : 1;
        }
        return new ServerSideView.Snapshot(null, bar, counts, new Material[27], null, null, null,
                new Material[4], null, 0, 20, 20, 0, false, false, false, 1000, 0);
    }

    @Test
    void bezKrumpaceZadneRudy() {
        BotNeeds needs = BotNeeds.assess(snapshot());
        assertEquals(0, needs.pickaxeTier());
        assertTrue(needs.miningWishlist().isEmpty(), "bez krumpáče nemá smysl chtít rudy");
    }

    @Test
    void sDrevenymChceKamenAUhli() {
        BotNeeds needs = BotNeeds.assess(snapshot(Material.WOODEN_PICKAXE));
        List<Material> wishlist = needs.miningWishlist();
        assertTrue(wishlist.contains(Material.STONE), "dřevěný krumpáč → chce kámen");
        assertTrue(wishlist.contains(Material.COAL_ORE), "bez pochodní → chce uhlí");
        assertFalse(wishlist.contains(Material.IRON_ORE),
                "železo dřevěným krumpáčem nevytěží (tier gating)");
    }

    @Test
    void sKamennymChceZelezo() {
        BotNeeds needs = BotNeeds.assess(snapshot(Material.STONE_PICKAXE, Material.TORCH));
        List<Material> wishlist = needs.miningWishlist();
        assertTrue(wishlist.contains(Material.IRON_ORE));
        assertTrue(wishlist.contains(Material.DEEPSLATE_IRON_ORE));
        assertFalse(wishlist.contains(Material.COAL_ORE), "pochodně má → uhlí nepotřebuje");
        assertFalse(wishlist.contains(Material.DIAMOND_ORE),
                "diamant kamenným krumpáčem nevytěží");
    }

    @Test
    void seZeleznymJdePoDiamantech() {
        BotNeeds needs = BotNeeds.assess(snapshot(Material.IRON_PICKAXE, Material.TORCH));
        assertTrue(needs.miningWishlist().contains(Material.DIAMOND_ORE));
        assertTrue(needs.canHarvest(Material.DIAMOND_ORE));
    }

    @Test
    void tierGatingOdpovidaVanille() {
        assertEquals(1, BotNeeds.requiredPickTier(Material.COAL_ORE));
        assertEquals(3, BotNeeds.requiredPickTier(Material.IRON_ORE));
        assertEquals(3, BotNeeds.requiredPickTier(Material.DEEPSLATE_LAPIS_ORE));
        assertEquals(4, BotNeeds.requiredPickTier(Material.DIAMOND_ORE));
        assertEquals(4, BotNeeds.requiredPickTier(Material.GOLD_ORE));
    }

    @Test
    void netherovyTierGating() {
        // Netherové zlato padá z libovolného krumpáče (na rozdíl od overworld
        // zlata) a trosky/obsidián jen z diamantového.
        assertEquals(1, BotNeeds.requiredPickTier(Material.NETHER_GOLD_ORE));
        assertEquals(1, BotNeeds.requiredPickTier(Material.NETHER_QUARTZ_ORE));
        assertEquals(1, BotNeeds.requiredPickTier(Material.GLOWSTONE));
        assertEquals(5, BotNeeds.requiredPickTier(Material.ANCIENT_DEBRIS));
        assertEquals(5, BotNeeds.requiredPickTier(Material.OBSIDIAN));
    }

    @Test
    void pripravaNaNetherVeWishlistu() {
        // Diamantový krumpáč bez křesadla → gravel (pazourek) a obsidián.
        BotNeeds needs = BotNeeds.assess(snapshot(Material.DIAMOND_PICKAXE, Material.TORCH));
        assertTrue(needs.miningWishlist().contains(Material.GRAVEL));
        assertTrue(needs.miningWishlist().contains(Material.OBSIDIAN));

        // S křesadlem gravel mizí, obsidián zůstává, dokud není 14 kusů.
        BotNeeds withFlint = BotNeeds.assess(
                snapshot(Material.DIAMOND_PICKAXE, Material.TORCH, Material.FLINT_AND_STEEL));
        assertFalse(withFlint.miningWishlist().contains(Material.GRAVEL));
        assertTrue(withFlint.miningWishlist().contains(Material.OBSIDIAN));

        // Železný krumpáč na nether přípravu nestačí.
        BotNeeds iron = BotNeeds.assess(snapshot(Material.IRON_PICKAXE, Material.TORCH));
        assertFalse(iron.miningWishlist().contains(Material.GRAVEL));
        assertFalse(iron.miningWishlist().contains(Material.OBSIDIAN));
    }

    @Test
    void rodinaRudNormalizujeDeepslate() {
        assertEquals("IRON_ORE", BotNeeds.oreFamily(Material.DEEPSLATE_IRON_ORE));
        assertEquals("IRON_ORE", BotNeeds.oreFamily(Material.IRON_ORE));
    }

    @Test
    void zelezoVInventariUkonciShaneniZeleza() {
        BotNeeds needs = BotNeeds.assess(
                snapshot(Material.STONE_PICKAXE, Material.TORCH, Material.RAW_IRON));
        assertFalse(needs.miningWishlist().contains(Material.IRON_ORE),
                "surové železo už má – teď ho má přetavit, ne kopat další");
    }

    @Test
    void nouzeJenPriHladuBezJidla() {
        BotNeeds hungry = BotNeeds.assess(snapshot());
        assertTrue(hungry.starving(6), "hlad 6 bez jídla = nouze");
        assertFalse(hungry.starving(15), "sytý bot není v nouzi");
        BotNeeds fed = BotNeeds.assess(snapshot(Material.BREAD));
        assertFalse(fed.starving(6), "má chleba – nají se, nekrade");
    }

    @Test
    void chudobaJenBezVsehoMajektu() {
        assertTrue(BotNeeds.assess(snapshot()).destitute(), "prázdný inventář = chudoba");
        assertFalse(BotNeeds.assess(snapshot(Material.WOODEN_PICKAXE)).destitute(),
                "krumpáč = může těžit, žádná nouze");
        assertFalse(BotNeeds.assess(snapshot(Material.OAK_LOG)).destitute(),
                "dřevo = může craftit, žádná nouze");
    }
}
