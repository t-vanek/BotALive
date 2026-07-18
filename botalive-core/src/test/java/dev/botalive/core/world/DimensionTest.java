package dev.botalive.core.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testy odvození dimenze z protokolového klíče světa.
 */
class DimensionTest {

    @Test
    void vanillaKlice() {
        assertEquals(Dimension.OVERWORLD, Dimension.fromWorldKey("minecraft:overworld"));
        assertEquals(Dimension.NETHER, Dimension.fromWorldKey("minecraft:the_nether"));
        assertEquals(Dimension.THE_END, Dimension.fromWorldKey("minecraft:the_end"));
    }

    @Test
    void vlastniNamespaceRozhodujeCesta() {
        // Jiný namespace, stejná cesta dimenze.
        assertEquals(Dimension.THE_END, Dimension.fromWorldKey("myserver:the_end"));
        assertEquals(Dimension.NETHER, Dimension.fromWorldKey("myserver:the_nether"));
    }

    @Test
    void pluginoveSvetyPodleKonvencniPripony() {
        // CraftBukkit klíčuje světy pluginů jako minecraft:<jméno_světa>.
        assertEquals(Dimension.THE_END, Dimension.fromWorldKey("minecraft:world_the_end"));
        assertEquals(Dimension.THE_END, Dimension.fromWorldKey("minecraft:mv_end"));
        assertEquals(Dimension.NETHER, Dimension.fromWorldKey("minecraft:world_the_nether"));
        assertEquals(Dimension.NETHER, Dimension.fromWorldKey("minecraft:mv_nether"));
    }

    @Test
    void neznameKliceJsouOverworld() {
        assertEquals(Dimension.OVERWORLD, Dimension.fromWorldKey("minecraft:custom_dim"));
        assertEquals(Dimension.OVERWORLD, Dimension.fromWorldKey("world_the_end_backup"));
        assertEquals(Dimension.OVERWORLD, Dimension.fromWorldKey("minecraft:weekend"));
        assertEquals(Dimension.OVERWORLD, Dimension.fromWorldKey(null));
    }
}
