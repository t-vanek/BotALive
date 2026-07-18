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
        // Multiverse a spol. – jiný namespace, stejná cesta dimenze.
        assertEquals(Dimension.THE_END, Dimension.fromWorldKey("myserver:the_end"));
        assertEquals(Dimension.NETHER, Dimension.fromWorldKey("myserver:the_nether"));
    }

    @Test
    void neznameKliceJsouOverworld() {
        assertEquals(Dimension.OVERWORLD, Dimension.fromWorldKey("minecraft:custom_dim"));
        assertEquals(Dimension.OVERWORLD, Dimension.fromWorldKey("world_the_end_backup"));
        assertEquals(Dimension.OVERWORLD, Dimension.fromWorldKey(null));
    }
}
