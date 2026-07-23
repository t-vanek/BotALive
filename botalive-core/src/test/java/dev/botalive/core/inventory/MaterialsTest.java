package dev.botalive.core.inventory;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Centrální katalog materiálů ({@link Materials}) – jeden zdroj pravdy pro
 * rudy, cennosti, odpad a stavební bloky napříč celou vanillou. Čisté
 * klasifikátory (bez registry), takže se testují přímo.
 */
class MaterialsTest {

    // ---- Rudy a hodnoty --------------------------------------------------

    @Test
    void isOrePokryvaVariantyIAncientDebris() {
        assertTrue(Materials.isOre(Material.IRON_ORE));
        assertTrue(Materials.isOre(Material.DEEPSLATE_DIAMOND_ORE));
        assertTrue(Materials.isOre(Material.NETHER_GOLD_ORE));
        assertTrue(Materials.isOre(Material.ANCIENT_DEBRIS));
        assertFalse(Materials.isOre(Material.STONE));
        assertFalse(Materials.isOre(Material.COAL)); // drop, ne ruda
        assertFalse(Materials.isOre(null));
    }

    @Test
    void oreValueVahneCelouVanillu() {
        assertEquals(2.0, Materials.oreValue(Material.COAL_ORE));
        assertEquals(2.5, Materials.oreValue(Material.COPPER_ORE));
        assertEquals(5.0, Materials.oreValue(Material.IRON_ORE));
        assertEquals(5.0, Materials.oreValue(Material.DEEPSLATE_IRON_ORE), "deepslate = stejná rodina");
        assertEquals(25.0, Materials.oreValue(Material.DIAMOND_ORE));
        assertEquals(1.5, Materials.oreValue(Material.NETHER_GOLD_ORE));
        assertEquals(1.5, Materials.oreValue(Material.NETHER_QUARTZ_ORE));
        assertEquals(30.0, Materials.oreValue(Material.ANCIENT_DEBRIS));
        assertNull(Materials.oreValue(Material.STONE));
        assertTrue(Materials.isValuableOre(Material.DEEPSLATE_GOLD_ORE));
        assertFalse(Materials.isValuableOre(Material.STONE));
    }

    @Test
    void tierGatingOdpovidaVanille() {
        // Parita s BotNeedsTest + měď (kamenný krumpáč).
        assertEquals(1, Materials.requiredPickTier(Material.COAL_ORE));
        assertEquals(3, Materials.requiredPickTier(Material.IRON_ORE));
        assertEquals(3, Materials.requiredPickTier(Material.COPPER_ORE));
        assertEquals(3, Materials.requiredPickTier(Material.DEEPSLATE_LAPIS_ORE));
        assertEquals(4, Materials.requiredPickTier(Material.DIAMOND_ORE));
        assertEquals(4, Materials.requiredPickTier(Material.GOLD_ORE));
        assertEquals(1, Materials.requiredPickTier(Material.NETHER_GOLD_ORE));
        assertEquals(1, Materials.requiredPickTier(Material.NETHER_QUARTZ_ORE));
        assertEquals(5, Materials.requiredPickTier(Material.ANCIENT_DEBRIS));
        assertEquals(5, Materials.requiredPickTier(Material.OBSIDIAN));
    }

    @Test
    void oreFamilyStripujeDeepslateNeNether() {
        assertEquals("IRON_ORE", Materials.oreFamily(Material.DEEPSLATE_IRON_ORE));
        assertEquals("IRON_ORE", Materials.oreFamily(Material.IRON_ORE));
        assertEquals("NETHER_GOLD_ORE", Materials.oreFamily(Material.NETHER_GOLD_ORE));
    }

    // ---- Bankovatelné cennosti ------------------------------------------

    @Test
    void bankableJsouCennostiNeOdpadAniNetherit() {
        assertTrue(Materials.isBankable(Material.RAW_IRON));
        assertTrue(Materials.isBankable(Material.IRON_INGOT));
        assertTrue(Materials.isBankable(Material.GOLD_INGOT));
        assertTrue(Materials.isBankable(Material.COPPER_INGOT));
        assertTrue(Materials.isBankable(Material.COAL));
        assertTrue(Materials.isBankable(Material.CHARCOAL));
        assertTrue(Materials.isBankable(Material.DIAMOND));
        assertTrue(Materials.isBankable(Material.LAPIS_LAZULI));
        assertTrue(Materials.isBankable(Material.QUARTZ));
        assertTrue(Materials.isBankable(Material.AMETHYST_SHARD));
        assertTrue(Materials.isBankable(Material.GLOWSTONE_DUST));
        assertTrue(Materials.isBankable(Material.GOLD_NUGGET));
        assertTrue(Materials.isBankable(Material.IRON_NUGGET));
        // Netheritový řetězec se schválně nebankuje.
        assertFalse(Materials.isBankable(Material.NETHERITE_INGOT));
        assertFalse(Materials.isBankable(Material.NETHERITE_SCRAP));
        assertFalse(Materials.isBankable(Material.ANCIENT_DEBRIS));
        // Odpad, spotřebák, blok surovin.
        assertFalse(Materials.isBankable(Material.COBBLESTONE));
        assertFalse(Materials.isBankable(Material.BREAD));
        assertFalse(Materials.isBankable(Material.RAW_IRON_BLOCK));
        assertFalse(Materials.isBankable(null));
    }

    @Test
    void bankReserveJeStedra() {
        assertEquals(16, Materials.bankReserve(Material.RAW_IRON));
        assertEquals(16, Materials.bankReserve(Material.IRON_INGOT));
        assertEquals(8, Materials.bankReserve(Material.GOLD_INGOT));
        assertEquals(32, Materials.bankReserve(Material.COAL));
        assertEquals(12, Materials.bankReserve(Material.DIAMOND));
        assertEquals(0, Materials.bankReserve(Material.NETHERITE_INGOT));
    }

    // ---- Odpad -----------------------------------------------------------

    @Test
    void bulkJunkJsouSypkeKamenyAHliny() {
        assertTrue(Materials.isBulkJunk(Material.COBBLESTONE));
        assertTrue(Materials.isBulkJunk(Material.DEEPSLATE));
        assertTrue(Materials.isBulkJunk(Material.DIRT));
        assertTrue(Materials.isBulkJunk(Material.GRAVEL));
        assertTrue(Materials.isBulkJunk(Material.GRANITE));
        assertTrue(Materials.isBulkJunk(Material.CALCITE));
        assertTrue(Materials.isBulkJunk(Material.BLACKSTONE));
        assertTrue(Materials.isBulkJunk(Material.ROTTEN_FLESH));
        assertFalse(Materials.isBulkJunk(Material.IRON_ORE), "rudu nezahazovat");
        assertFalse(Materials.isBulkJunk(Material.OAK_PLANKS));
        assertFalse(Materials.isBulkJunk(Material.DIAMOND));
        assertFalse(Materials.isBulkJunk(null));
    }

    // ---- Stavební bloky (plné kvádry) -----------------------------------

    @Test
    void buildingBlockPokryvaPlneKvadry() {
        assertTrue(Materials.isBuildingBlock(Material.OAK_PLANKS));
        assertTrue(Materials.isBuildingBlock(Material.SPRUCE_PLANKS));
        assertTrue(Materials.isBuildingBlock(Material.OAK_LOG));
        assertTrue(Materials.isBuildingBlock(Material.STRIPPED_OAK_LOG));
        assertTrue(Materials.isBuildingBlock(Material.OAK_WOOD));
        assertTrue(Materials.isBuildingBlock(Material.CRIMSON_STEM));
        assertTrue(Materials.isBuildingBlock(Material.CRIMSON_HYPHAE));
        assertTrue(Materials.isBuildingBlock(Material.STONE));
        assertTrue(Materials.isBuildingBlock(Material.COBBLESTONE));
        assertTrue(Materials.isBuildingBlock(Material.COBBLED_DEEPSLATE));
        assertTrue(Materials.isBuildingBlock(Material.STONE_BRICKS));
        assertTrue(Materials.isBuildingBlock(Material.NETHER_BRICKS));
        assertTrue(Materials.isBuildingBlock(Material.BRICKS));
        assertTrue(Materials.isBuildingBlock(Material.END_STONE));
        assertTrue(Materials.isBuildingBlock(Material.SANDSTONE));
        assertTrue(Materials.isBuildingBlock(Material.GRANITE));
        assertTrue(Materials.isBuildingBlock(Material.POLISHED_ANDESITE));
        assertTrue(Materials.isBuildingBlock(Material.QUARTZ_BLOCK));
        assertTrue(Materials.isBuildingBlock(Material.DIRT));
        assertTrue(Materials.isBuildingBlock(Material.WHITE_CONCRETE));
        assertTrue(Materials.isBuildingBlock(Material.WHITE_TERRACOTTA));
        assertTrue(Materials.isBuildingBlock(Material.TERRACOTTA));
    }

    @Test
    void buildingBlockVylucujeDelenaCennostiDekor() {
        // Dělené bloky – nejsou plný kvádr.
        assertFalse(Materials.isBuildingBlock(Material.OAK_SLAB));
        assertFalse(Materials.isBuildingBlock(Material.OAK_STAIRS));
        assertFalse(Materials.isBuildingBlock(Material.COBBLESTONE_WALL));
        assertFalse(Materials.isBuildingBlock(Material.OAK_FENCE));
        assertFalse(Materials.isBuildingBlock(Material.OAK_DOOR));
        assertFalse(Materials.isBuildingBlock(Material.OAK_TRAPDOOR));
        assertFalse(Materials.isBuildingBlock(Material.STONE_BRICK_STAIRS));
        // Sklo, dekor, sazenice, listí.
        assertFalse(Materials.isBuildingBlock(Material.GLASS));
        assertFalse(Materials.isBuildingBlock(Material.GLASS_PANE));
        assertFalse(Materials.isBuildingBlock(Material.OAK_LEAVES));
        assertFalse(Materials.isBuildingBlock(Material.OAK_SAPLING));
        assertFalse(Materials.isBuildingBlock(Material.WHITE_CARPET));
        // Cennosti a rudy – nepilířovat s nimi.
        assertFalse(Materials.isBuildingBlock(Material.DIAMOND_BLOCK));
        assertFalse(Materials.isBuildingBlock(Material.GOLD_BLOCK));
        assertFalse(Materials.isBuildingBlock(Material.IRON_ORE));
        assertFalse(Materials.isBuildingBlock(Material.REDSTONE));
        // Gravitační prášek betonu není kvádr.
        assertFalse(Materials.isBuildingBlock(Material.WHITE_CONCRETE_POWDER));
        assertFalse(Materials.isBuildingBlock(null));
    }

    @Test
    void woodPokryvaCelouVanilluDreva() {
        assertTrue(Materials.isWood(Material.OAK_PLANKS));
        assertTrue(Materials.isWood(Material.OAK_LOG));
        assertTrue(Materials.isWood(Material.STRIPPED_OAK_LOG));
        assertTrue(Materials.isWood(Material.OAK_WOOD));
        assertTrue(Materials.isWood(Material.CRIMSON_STEM), "nether dřevo se počítá");
        assertTrue(Materials.isWood(Material.CRIMSON_HYPHAE));
        assertFalse(Materials.isWood(Material.STONE));
        assertFalse(Materials.isWood(Material.STICK), "klacek není dřevo (polotovar)");
        assertFalse(Materials.isWood(null));
        assertTrue(Materials.isPlanks(Material.SPRUCE_PLANKS));
        assertFalse(Materials.isPlanks(Material.OAK_LOG));
    }

    @Test
    void listiAKamenNaturalni() {
        assertTrue(Materials.isLeaves(Material.OAK_LEAVES));
        assertTrue(Materials.isLeaves(Material.AZALEA_LEAVES));
        assertFalse(Materials.isLeaves(Material.OAK_LOG));
        assertFalse(Materials.isLeaves(null));
        assertTrue(Materials.isStone(Material.STONE));
        assertTrue(Materials.isStone(Material.GRANITE));
        assertTrue(Materials.isStone(Material.DEEPSLATE));
        assertTrue(Materials.isStone(Material.TUFF));
        assertFalse(Materials.isStone(Material.COBBLESTONE), "dlažba není přírodní kámen");
        assertFalse(Materials.isStone(Material.OAK_PLANKS));
    }

    @Test
    void tvaryBloku() {
        assertTrue(Materials.isSlab(Material.OAK_SLAB));
        assertTrue(Materials.isStairs(Material.STONE_BRICK_STAIRS));
        assertTrue(Materials.isWall(Material.COBBLESTONE_WALL));
        assertTrue(Materials.isFence(Material.OAK_FENCE));
        assertFalse(Materials.isFence(Material.OAK_FENCE_GATE), "branka není plot");
        assertTrue(Materials.isFenceGate(Material.OAK_FENCE_GATE));
        assertTrue(Materials.isDoor(Material.OAK_DOOR));
        assertFalse(Materials.isDoor(Material.OAK_TRAPDOOR), "padací dveře nejsou dveře");
        assertTrue(Materials.isTrapdoor(Material.OAK_TRAPDOOR));
    }

    @Test
    void materialoveRodiny() {
        assertTrue(Materials.isGlass(Material.GLASS));
        assertTrue(Materials.isGlass(Material.WHITE_STAINED_GLASS));
        assertTrue(Materials.isGlass(Material.GLASS_PANE));
        assertFalse(Materials.isGlass(Material.GLASS_BOTTLE), "láhev není sklo-blok");
        assertTrue(Materials.isWool(Material.WHITE_WOOL));
        assertTrue(Materials.isCarpet(Material.RED_CARPET));
        assertTrue(Materials.isConcrete(Material.WHITE_CONCRETE));
        assertFalse(Materials.isConcrete(Material.WHITE_CONCRETE_POWDER));
        assertTrue(Materials.isConcretePowder(Material.WHITE_CONCRETE_POWDER));
        assertTrue(Materials.isTerracotta(Material.TERRACOTTA));
        assertTrue(Materials.isTerracotta(Material.WHITE_GLAZED_TERRACOTTA));
        assertTrue(Materials.isDeepslate(Material.COBBLED_DEEPSLATE));
        assertTrue(Materials.isDeepslate(Material.DEEPSLATE_IRON_ORE));
        assertFalse(Materials.isDeepslate(Material.STONE));
    }

    @Test
    void sypkeLedMineralni() {
        assertTrue(Materials.isSand(Material.SAND));
        assertTrue(Materials.isSand(Material.RED_SAND));
        assertTrue(Materials.isIce(Material.PACKED_ICE));
        assertTrue(Materials.isGravityBlock(Material.GRAVEL));
        assertTrue(Materials.isGravityBlock(Material.SAND));
        assertTrue(Materials.isGravityBlock(Material.WHITE_CONCRETE_POWDER));
        assertTrue(Materials.isGravityBlock(Material.ANVIL));
        assertTrue(Materials.isGravityBlock(Material.POINTED_DRIPSTONE));
        assertTrue(Materials.isGravityBlock(Material.DRAGON_EGG));
        assertTrue(Materials.isGravityBlock(Material.SUSPICIOUS_GRAVEL));
        assertFalse(Materials.isGravityBlock(Material.STONE));
        assertTrue(Materials.isMineralBlock(Material.IRON_BLOCK));
        assertTrue(Materials.isMineralBlock(Material.RAW_IRON_BLOCK));
        assertTrue(Materials.isMineralBlock(Material.DIAMOND_BLOCK));
        assertFalse(Materials.isMineralBlock(Material.QUARTZ_BLOCK), "křemenný blok je stavební");
        assertFalse(Materials.isMineralBlock(Material.STONE));
    }

    @Test
    void isLogNestripovanaKladaNeboKmen() {
        assertTrue(Materials.isLog(Material.OAK_LOG));
        assertTrue(Materials.isLog(Material.CRIMSON_STEM));
        assertFalse(Materials.isLog(Material.STRIPPED_OAK_LOG));
        assertFalse(Materials.isLog(Material.OAK_PLANKS));
        assertFalse(Materials.isLog(Material.OAK_WOOD));
        assertFalse(Materials.isLog(null));
    }
}
