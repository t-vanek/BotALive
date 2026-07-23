package dev.botalive.core.inventory;

import dev.botalive.core.inventory.Items.ItemCategory;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Katalog non-blokových itemů ({@link Items}) – roztřídění do kategorií.
 * Čisté klasifikátory (konstanty + názvové vzory), testovatelné bez serveru.
 */
class ItemsTest {

    @Test
    void nastroje() {
        assertTrue(Items.isTool(Material.DIAMOND_PICKAXE));
        assertTrue(Items.isTool(Material.IRON_AXE));
        assertTrue(Items.isTool(Material.WOODEN_SHOVEL));
        assertTrue(Items.isTool(Material.STONE_HOE));
        assertTrue(Items.isTool(Material.SHEARS));
        assertTrue(Items.isTool(Material.FLINT_AND_STEEL));
        assertTrue(Items.isTool(Material.FISHING_ROD));
        assertFalse(Items.isTool(Material.DIAMOND_SWORD), "meč je zbraň");
        assertFalse(Items.isTool(Material.BOW));
        assertFalse(Items.isTool(null));
    }

    @Test
    void podtypyNastroju() {
        assertTrue(Items.isPickaxe(Material.IRON_PICKAXE));
        assertFalse(Items.isPickaxe(Material.IRON_AXE));
        assertTrue(Items.isAxe(Material.IRON_AXE));
        assertFalse(Items.isAxe(Material.IRON_PICKAXE), "krumpáč není sekera");
        assertTrue(Items.isShovel(Material.IRON_SHOVEL));
        assertTrue(Items.isHoe(Material.IRON_HOE));
        assertTrue(Items.isSword(Material.DIAMOND_SWORD));
        assertFalse(Items.isSword(Material.DIAMOND_PICKAXE));
        assertTrue(Items.isMeleeWeapon(Material.DIAMOND_SWORD));
        assertTrue(Items.isMeleeWeapon(Material.TRIDENT));
        assertFalse(Items.isMeleeWeapon(Material.BOW));
    }

    @Test
    void podtypyDopravyADalsi() {
        assertTrue(Items.isMinecart(Material.MINECART));
        assertTrue(Items.isMinecart(Material.CHEST_MINECART));
        assertFalse(Items.isMinecart(Material.OAK_BOAT));
        assertTrue(Items.isRail(Material.RAIL));
        assertTrue(Items.isRail(Material.POWERED_RAIL));
        assertFalse(Items.isRail(Material.MINECART));
        assertTrue(Items.isSmithingTemplate(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        assertTrue(Items.isSpawnEgg(Material.ZOMBIE_SPAWN_EGG));
        assertFalse(Items.isSpawnEgg(Material.EGG));
        assertTrue(Items.isHorseArmor(Material.IRON_HORSE_ARMOR));
    }

    @Test
    void zbraneAStrelivo() {
        assertTrue(Items.isWeapon(Material.DIAMOND_SWORD));
        assertTrue(Items.isWeapon(Material.BOW));
        assertTrue(Items.isWeapon(Material.CROSSBOW));
        assertTrue(Items.isWeapon(Material.TRIDENT));
        assertTrue(Items.isRangedWeapon(Material.BOW));
        assertFalse(Items.isRangedWeapon(Material.DIAMOND_SWORD));
        assertFalse(Items.isWeapon(Material.DIAMOND_PICKAXE));
        assertTrue(Items.isAmmo(Material.ARROW));
        assertTrue(Items.isAmmo(Material.SPECTRAL_ARROW));
        assertTrue(Items.isAmmo(Material.FIREWORK_ROCKET));
        assertFalse(Items.isAmmo(Material.BOW));
    }

    @Test
    void brneniANositelne() {
        assertTrue(Items.isArmor(Material.IRON_CHESTPLATE));
        assertTrue(Items.isArmor(Material.DIAMOND_HELMET));
        assertTrue(Items.isArmor(Material.TURTLE_HELMET));
        assertTrue(Items.isArmor(Material.LEATHER_BOOTS));
        assertFalse(Items.isArmor(Material.ELYTRA), "elytra není slot brnění");
        assertTrue(Items.isWearable(Material.ELYTRA), "ale je nositelná");
        assertTrue(Items.isShield(Material.SHIELD));
        assertFalse(Items.isArmor(Material.SHIELD));
        assertFalse(Items.isArmor(Material.DIAMOND_SWORD));
    }

    @Test
    void jidloLektvaryVareni() {
        assertTrue(Items.isFood(Material.BREAD));
        assertTrue(Items.isFood(Material.COOKED_BEEF));
        assertFalse(Items.isFood(Material.COBBLESTONE));
        assertTrue(Items.isPotion(Material.POTION));
        assertTrue(Items.isPotion(Material.SPLASH_POTION));
        assertTrue(Items.isPotion(Material.LINGERING_POTION));
        assertFalse(Items.isPotion(Material.GLASS_BOTTLE));
        assertTrue(Items.isBrewingIngredient(Material.NETHER_WART));
        assertTrue(Items.isBrewingIngredient(Material.BLAZE_POWDER));
        assertTrue(Items.isBrewingIngredient(Material.GLASS_BOTTLE));
        assertTrue(Items.isBrewingIngredient(Material.FERMENTED_SPIDER_EYE));
        assertFalse(Items.isBrewingIngredient(Material.BREAD));
    }

    @Test
    void osivoASazenice() {
        assertTrue(Items.isSeed(Material.WHEAT_SEEDS));
        assertTrue(Items.isSeed(Material.BEETROOT_SEEDS));
        assertTrue(Items.isSeed(Material.PUMPKIN_SEEDS));
        assertTrue(Items.isSeed(Material.NETHER_WART));
        assertTrue(Items.isSeed(Material.COCOA_BEANS));
        assertFalse(Items.isSeed(Material.WHEAT), "pšenice není osivo");
        assertTrue(Items.isSapling(Material.OAK_SAPLING));
        assertFalse(Items.isSapling(Material.OAK_LOG));
    }

    @Test
    void dopravaUtilityBarviva() {
        assertTrue(Items.isTransport(Material.OAK_BOAT));
        assertTrue(Items.isTransport(Material.OAK_CHEST_BOAT));
        assertTrue(Items.isTransport(Material.BAMBOO_RAFT), "bambusový prám je doprava");
        assertTrue(Items.isBoat(Material.OAK_BOAT));
        assertTrue(Items.isBoat(Material.BAMBOO_RAFT), "prám je loďka");
        assertTrue(Items.isBoat(Material.BAMBOO_CHEST_RAFT));
        assertFalse(Items.isBoat(Material.MINECART));
        assertFalse(Items.isBoat(null));
        assertTrue(Items.isTransport(Material.MINECART));
        assertTrue(Items.isTransport(Material.CHEST_MINECART));
        assertTrue(Items.isTransport(Material.RAIL));
        assertTrue(Items.isTransport(Material.POWERED_RAIL));
        assertTrue(Items.isTransport(Material.SADDLE));
        assertTrue(Items.isTransport(Material.ELYTRA));
        assertFalse(Items.isTransport(Material.DIAMOND));

        assertTrue(Items.isBucket(Material.BUCKET));
        assertTrue(Items.isBucket(Material.WATER_BUCKET));
        assertTrue(Items.isBucket(Material.LAVA_BUCKET));
        assertTrue(Items.isUtility(Material.COMPASS));
        assertTrue(Items.isUtility(Material.NAME_TAG));
        assertTrue(Items.isUtility(Material.WATER_BUCKET), "kýbl je utility");
        assertFalse(Items.isUtility(Material.DIAMOND));

        assertTrue(Items.isDye(Material.RED_DYE));
        assertTrue(Items.isDye(Material.BONE_MEAL));
        assertFalse(Items.isDye(Material.STRING));
    }

    @Test
    void palivo() {
        assertTrue(Items.isFuel(Material.COAL));
        assertTrue(Items.isFuel(Material.CHARCOAL));
        assertTrue(Items.isFuel(Material.OAK_PLANKS));
        assertTrue(Items.isFuel(Material.OAK_LOG));
        assertTrue(Items.isFuel(Material.STICK));
        assertTrue(Items.isFuel(Material.BAMBOO));
        assertTrue(Items.isFuel(Material.LAVA_BUCKET));
        assertFalse(Items.isFuel(Material.IRON_INGOT));
        assertFalse(Items.isFuel(Material.STONE));
    }

    @Test
    void cennostiADropy() {
        assertTrue(Items.isValuable(Material.DIAMOND), "bankovatelné je cenné");
        assertTrue(Items.isValuable(Material.IRON_INGOT));
        assertTrue(Items.isValuable(Material.NETHER_STAR));
        assertTrue(Items.isValuable(Material.TOTEM_OF_UNDYING));
        assertTrue(Items.isValuable(Material.ENCHANTED_BOOK));
        assertTrue(Items.isValuable(Material.NETHERITE_INGOT));
        assertTrue(Items.isValuable(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        assertFalse(Items.isValuable(Material.COBBLESTONE));

        assertTrue(Items.isMobDrop(Material.STRING));
        assertTrue(Items.isMobDrop(Material.FEATHER));
        assertTrue(Items.isMobDrop(Material.LEATHER));
        assertTrue(Items.isMobDrop(Material.BONE));
        assertTrue(Items.isMobDrop(Material.ENDER_PEARL));
        assertFalse(Items.isMobDrop(Material.DIAMOND));
    }

    @Test
    void kovyDrahokamy() {
        assertTrue(Items.isIngot(Material.IRON_INGOT));
        assertTrue(Items.isIngot(Material.NETHERITE_INGOT));
        assertFalse(Items.isIngot(Material.IRON_NUGGET));
        assertTrue(Items.isNugget(Material.GOLD_NUGGET));
        assertTrue(Items.isRawMetal(Material.RAW_IRON));
        assertFalse(Items.isRawMetal(Material.RAW_IRON_BLOCK), "blok surové rudy není surový kov");
        assertTrue(Items.isGem(Material.DIAMOND));
        assertTrue(Items.isGem(Material.LAPIS_LAZULI));
        assertFalse(Items.isGem(Material.COAL));
    }

    @Test
    void jidloKnihyMisc() {
        assertTrue(Items.isRawFood(Material.BEEF));
        assertTrue(Items.isRawFood(Material.COD));
        assertFalse(Items.isRawFood(Material.COOKED_BEEF));
        assertTrue(Items.isCookedFood(Material.COOKED_BEEF));
        assertTrue(Items.isCookedFood(Material.COOKED_SALMON));
        assertFalse(Items.isCookedFood(Material.BEEF));
        assertTrue(Items.isBook(Material.BOOK));
        assertTrue(Items.isBook(Material.ENCHANTED_BOOK));
        assertFalse(Items.isBook(Material.PAPER));
        assertTrue(Items.isBanner(Material.WHITE_BANNER));
        assertTrue(Items.isBed(Material.RED_BED));
        assertTrue(Items.isHead(Material.SKELETON_SKULL));
        assertTrue(Items.isHead(Material.PLAYER_HEAD));
        assertFalse(Items.isHead(Material.STONE));
        assertTrue(Items.isMap(Material.FILLED_MAP));
        assertTrue(Items.isThrowable(Material.SNOWBALL));
        assertTrue(Items.isThrowable(Material.SPLASH_POTION));
        assertFalse(Items.isThrowable(Material.ARROW));
    }

    @Test
    void hlavniKategoriePrioritizuje() {
        assertEquals(ItemCategory.ARMOR, Items.primaryCategory(Material.DIAMOND_HELMET));
        assertEquals(ItemCategory.ARMOR, Items.primaryCategory(Material.ELYTRA));
        assertEquals(ItemCategory.ARMOR, Items.primaryCategory(Material.SHIELD));
        assertEquals(ItemCategory.WEAPON, Items.primaryCategory(Material.DIAMOND_SWORD));
        assertEquals(ItemCategory.TOOL, Items.primaryCategory(Material.DIAMOND_PICKAXE));
        assertEquals(ItemCategory.AMMO, Items.primaryCategory(Material.ARROW));
        assertEquals(ItemCategory.POTION, Items.primaryCategory(Material.SPLASH_POTION));
        assertEquals(ItemCategory.FOOD, Items.primaryCategory(Material.BREAD));
        assertEquals(ItemCategory.TRANSPORT, Items.primaryCategory(Material.OAK_BOAT));
        assertEquals(ItemCategory.SEED, Items.primaryCategory(Material.WHEAT_SEEDS));
        assertEquals(ItemCategory.BREWING, Items.primaryCategory(Material.BLAZE_POWDER));
        assertEquals(ItemCategory.UTILITY, Items.primaryCategory(Material.COMPASS));
        assertEquals(ItemCategory.DYE, Items.primaryCategory(Material.RED_DYE));
        // coal je palivo i bankovatelné → palivo má přednost před cenností.
        assertEquals(ItemCategory.FUEL, Items.primaryCategory(Material.COAL));
        assertEquals(ItemCategory.VALUABLE, Items.primaryCategory(Material.DIAMOND));
        assertEquals(ItemCategory.MOB_DROP, Items.primaryCategory(Material.STRING));
        assertEquals(ItemCategory.BLOCK, Items.primaryCategory(Material.COBBLESTONE));
        assertEquals(ItemCategory.OTHER, Items.primaryCategory(null));
    }
}
