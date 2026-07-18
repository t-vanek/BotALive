package dev.botalive.core.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testy heuristiky dimenze z klíče světa (packet režim).
 */
class WorldDimensionTest {

    @Test
    void vanillaKlice() {
        assertEquals(WorldDimension.OVERWORLD, WorldDimension.fromWorldKey("minecraft:overworld"));
        assertEquals(WorldDimension.NETHER, WorldDimension.fromWorldKey("minecraft:the_nether"));
        assertEquals(WorldDimension.END, WorldDimension.fromWorldKey("minecraft:the_end"));
    }

    @Test
    void bukkitNazvySvetu() {
        assertEquals(WorldDimension.OVERWORLD, WorldDimension.fromWorldKey("world"));
        assertEquals(WorldDimension.NETHER, WorldDimension.fromWorldKey("world_nether"));
        assertEquals(WorldDimension.END, WorldDimension.fromWorldKey("world_the_end"));
    }

    @Test
    void velikostPismenNerozhoduje() {
        assertEquals(WorldDimension.NETHER, WorldDimension.fromWorldKey("World_Nether"));
    }

    @Test
    void neznameKliceJsouOverworld() {
        assertEquals(WorldDimension.OVERWORLD, WorldDimension.fromWorldKey("minecraft:creative"));
        assertEquals(WorldDimension.OVERWORLD, WorldDimension.fromWorldKey("skyblock"));
        // „nether" uprostřed slova bez oddělovače nestačí.
        assertEquals(WorldDimension.OVERWORLD, WorldDimension.fromWorldKey("netherlands_city"));
        // Overworld mapy s „end" v názvu nesmí boty vypnout (Bukkit end
        // světy končí na „_the_end", bare „_end" je běžné jméno mapy).
        assertEquals(WorldDimension.OVERWORLD, WorldDimension.fromWorldKey("west_end"));
        assertEquals(WorldDimension.OVERWORLD, WorldDimension.fromWorldKey("deep_end"));
    }

    @Test
    void prazdnyKlicJeUnknown() {
        assertEquals(WorldDimension.UNKNOWN, WorldDimension.fromWorldKey(""));
        assertEquals(WorldDimension.UNKNOWN, WorldDimension.fromWorldKey(null));
    }
}
