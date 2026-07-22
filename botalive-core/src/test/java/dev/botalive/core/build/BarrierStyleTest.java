package dev.botalive.core.build;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testy volby materiálu bariéry – plot z místního dřeva (fallback dub) a
 * kamenná hradba s dřevěnou brankou.
 */
class BarrierStyleTest {

    @Test
    void plotZMistnihoDreva() {
        BarrierStyle.Materials oak = BarrierStyle.FENCE.materials(Material.OAK_LOG);
        assertEquals(Material.OAK_FENCE, oak.post());
        assertEquals(Material.OAK_FENCE_GATE, oak.gate());

        BarrierStyle.Materials spruce = BarrierStyle.FENCE.materials(Material.SPRUCE_PLANKS);
        assertEquals(Material.SPRUCE_FENCE, spruce.post());
        assertEquals(Material.SPRUCE_FENCE_GATE, spruce.gate());
    }

    @Test
    void neznameNeboZadneDrevoSpadneNaDub() {
        assertEquals(Material.OAK_FENCE, BarrierStyle.FENCE.materials(Material.STONE).post());
        assertEquals(Material.OAK_FENCE, BarrierStyle.FENCE.materials(null).post());
    }

    @Test
    void hradbaJeKamennaSDrevěnouBrankou() {
        // U hradby se dřevo ignoruje – je vždy kamenná.
        BarrierStyle.Materials wall = BarrierStyle.WALL.materials(Material.SPRUCE_LOG);
        assertEquals(Material.COBBLESTONE_WALL, wall.post());
        assertEquals(Material.OAK_FENCE_GATE, wall.gate());
    }
}
