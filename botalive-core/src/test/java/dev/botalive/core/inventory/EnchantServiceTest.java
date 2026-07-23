package dev.botalive.core.inventory;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enchant politika ({@link EnchantService#isEnchantable}) – postavená na
 * katalogu {@link Items} + záměrné výjimky. Test zamyká, co bot očarovává,
 * a hlídá, že přechod na katalog nezměnil chování.
 */
class EnchantServiceTest {

    @Test
    void ocarovavaZbraneNastrojeBrneni() {
        assertTrue(EnchantService.isEnchantable(Material.DIAMOND_SWORD));
        assertTrue(EnchantService.isEnchantable(Material.IRON_PICKAXE));
        assertTrue(EnchantService.isEnchantable(Material.IRON_AXE));
        assertTrue(EnchantService.isEnchantable(Material.IRON_SHOVEL));
        assertTrue(EnchantService.isEnchantable(Material.BOW));
        assertTrue(EnchantService.isEnchantable(Material.CROSSBOW));
        assertTrue(EnchantService.isEnchantable(Material.DIAMOND_CHESTPLATE));
        assertTrue(EnchantService.isEnchantable(Material.TURTLE_HELMET));
    }

    @Test
    void neocarovavaVyjimky() {
        // Katalog je zná (nástroj/zbraň), ale politika je schválně vynechává.
        assertFalse(EnchantService.isEnchantable(Material.IRON_HOE));
        assertFalse(EnchantService.isEnchantable(Material.TRIDENT));
        assertFalse(EnchantService.isEnchantable(Material.SHEARS));
        assertFalse(EnchantService.isEnchantable(Material.FISHING_ROD));
        assertFalse(EnchantService.isEnchantable(Material.ELYTRA));
        assertFalse(EnchantService.isEnchantable(Material.SHIELD));
        assertFalse(EnchantService.isEnchantable(Material.DIAMOND));
    }
}
