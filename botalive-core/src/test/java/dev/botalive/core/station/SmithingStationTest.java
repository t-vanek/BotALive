package dev.botalive.core.station;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Testy mapování povýšení diamant → netherit.
 */
class SmithingStationTest {

    @Test
    void diamantoveKusySeMapujiNaNetherit() {
        assertEquals(Material.NETHERITE_PICKAXE,
                SmithingStation.netheriteOf(Material.DIAMOND_PICKAXE));
        assertEquals(Material.NETHERITE_SWORD,
                SmithingStation.netheriteOf(Material.DIAMOND_SWORD));
        assertEquals(Material.NETHERITE_CHESTPLATE,
                SmithingStation.netheriteOf(Material.DIAMOND_CHESTPLATE));
        assertEquals(Material.NETHERITE_BOOTS,
                SmithingStation.netheriteOf(Material.DIAMOND_BOOTS));
    }

    @Test
    void kazdyKandidatPoradiMaNetheritovouPodobu() {
        for (String candidate : SmithingStation.UPGRADE_ORDER) {
            assertEquals("NETHERITE_" + candidate.substring("DIAMOND_".length()),
                    SmithingStation.netheriteOf(Material.valueOf(candidate)).name(),
                    candidate + " musí mít netheritový protějšek");
        }
    }

    @Test
    void nediamantoveKusyNejdouPovysit() {
        assertNull(SmithingStation.netheriteOf(Material.IRON_PICKAXE));
        assertNull(SmithingStation.netheriteOf(Material.DIAMOND));
        assertNull(SmithingStation.netheriteOf(Material.DIAMOND_BLOCK));
        assertNull(SmithingStation.netheriteOf(null));
    }
}
