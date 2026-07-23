package dev.botalive.core.build.plan;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Build wishlist – suroviny k natěžení pro tier domu (čistá funkce). */
class BuildMaterialsTest {

    @Test
    void srubNechceZadneSuroviny() {
        // Srub má okna jako otvory – žádné sklo, nic k shánění.
        assertTrue(BuildMaterials.gatherWishlist(BuildTier.PROVISIONAL, false, false).isEmpty());
        assertTrue(BuildMaterials.gatherWishlist(null, false, false).isEmpty());
    }

    @Test
    void solidniAVyssiChcePisekNaSklo() {
        assertEquals(List.of(Material.SAND, Material.RED_SAND),
                BuildMaterials.gatherWishlist(BuildTier.SOLID, false, false));
        assertEquals(List.of(Material.SAND, Material.RED_SAND),
                BuildMaterials.gatherWishlist(BuildTier.REFINED, false, false));
    }

    @Test
    void sesklemNeboPiskemUzNic() {
        // Sklo už má → nezasklívá se znovu.
        assertTrue(BuildMaterials.gatherWishlist(BuildTier.SOLID, true, false).isEmpty());
        // Písek už má → taví se na sklo, další netřeba.
        assertTrue(BuildMaterials.gatherWishlist(BuildTier.REFINED, false, true).isEmpty());
    }
}
