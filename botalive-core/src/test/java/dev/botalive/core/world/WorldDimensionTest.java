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

    @Test
    void dimensionTypeJeAutoritativni() {
        // Custom svět „mv_end" s overworld typem je overworld (postel OK)...
        assertEquals(WorldDimension.OVERWORLD, WorldDimension.fromDimensionType(
                "minecraft:overworld", "minecraft:mv_end"));
        // ...a naopak nenápadné jméno s typem the_end je End (postel bouchá!).
        assertEquals(WorldDimension.END, WorldDimension.fromDimensionType(
                "minecraft:the_end", "minecraft:dragonworld"));
        assertEquals(WorldDimension.NETHER, WorldDimension.fromDimensionType(
                "minecraft:the_nether", "minecraft:hell_custom"));
        // Custom typ datapacku: heuristika typu, pak jména světa.
        assertEquals(WorldDimension.END, WorldDimension.fromDimensionType(
                "mypack:floating_the_end", "minecraft:islands"));
        assertEquals(WorldDimension.NETHER, WorldDimension.fromDimensionType(
                "mypack:custom", "minecraft:world_nether"));
        // Bez typu zůstává heuristika jména.
        assertEquals(WorldDimension.END, WorldDimension.fromDimensionType(
                null, "minecraft:world_the_end"));
    }
}
