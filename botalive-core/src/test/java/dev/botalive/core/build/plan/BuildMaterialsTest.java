package dev.botalive.core.build.plan;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Build wishlist – suroviny k natěžení pro tier domu (čistá funkce). */
class BuildMaterialsTest {

    @Test
    void srubNechceZadneSuroviny() {
        // Srub má okna jako otvory a zdi ze dřeva – žádné suroviny.
        assertTrue(BuildMaterials.gatherWishlist(BuildTier.PROVISIONAL, false, false, false, false)
                .isEmpty());
        assertTrue(BuildMaterials.gatherWishlist(null, false, false, false, false).isEmpty());
    }

    @Test
    void solidniChcePisekNaSklo() {
        assertEquals(List.of(Material.SAND, Material.RED_SAND),
                BuildMaterials.gatherWishlist(BuildTier.SOLID, false, false, false, false));
        // Sklo/písek už má → sklo neřeší; solidní nechce hlínu.
        assertTrue(BuildMaterials.gatherWishlist(BuildTier.SOLID, true, false, false, false)
                .isEmpty());
        assertTrue(BuildMaterials.gatherWishlist(BuildTier.SOLID, false, true, false, false)
                .isEmpty());
    }

    @Test
    void refinedChcePisekIHlinu() {
        // Reprezentativní dům: sklo (písek) i cihly (hlína).
        List<Material> want = BuildMaterials.gatherWishlist(
                BuildTier.REFINED, false, false, false, false);
        assertTrue(want.contains(Material.SAND), "písek na sklo");
        assertTrue(want.contains(Material.CLAY), "hlína na cihly");
    }

    @Test
    void hlinuNesbiraKdyzJiZpracovavaNeboMaCihly() {
        // Už má hlínu/kuličky v tavbě → další nesbírá.
        assertFalse(BuildMaterials.gatherWishlist(BuildTier.REFINED, true, true, true, false)
                .contains(Material.CLAY), "hlína se zpracovává");
        // Už má hotové cihlové bloky → tuhle etapu má pokrytou.
        assertFalse(BuildMaterials.gatherWishlist(BuildTier.REFINED, true, true, false, true)
                .contains(Material.CLAY), "cihly už jsou");
        // Solidní dům hlínu nechce vůbec.
        assertFalse(BuildMaterials.gatherWishlist(BuildTier.SOLID, false, false, false, false)
                .contains(Material.CLAY), "solidní dům cihly nepotřebuje");
    }
}
