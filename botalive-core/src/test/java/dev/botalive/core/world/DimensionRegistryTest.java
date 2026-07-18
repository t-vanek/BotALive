package dev.botalive.core.world;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Testy parsování registru dimenzí a biomů.
 */
class DimensionRegistryTest {

    private static RegistryEntry dimension(String id, int minY, int height) {
        NbtMap data = NbtMap.builder()
                .putInt("min_y", minY)
                .putInt("height", height)
                .build();
        return new RegistryEntry(Key.key(id), data);
    }

    @Test
    void parsujeDimenzeZRegistru() {
        DimensionRegistry registry = new DimensionRegistry();
        registry.accept(Key.key("minecraft:dimension_type"), List.of(
                dimension("minecraft:overworld", -64, 384),
                dimension("minecraft:the_nether", 0, 256)));

        assertEquals(2, registry.dimensionCount());
        assertEquals(-64, registry.dimensionInfo(0).minY());
        assertEquals(384, registry.dimensionInfo(0).height());
        assertEquals(24, registry.dimensionInfo(0).sectionCount());
        assertEquals(0, registry.dimensionInfo(1).minY());
        assertEquals(16, registry.dimensionInfo(1).sectionCount());
    }

    @Test
    void neznamyIndexDaOverworldDefault() {
        DimensionRegistry registry = new DimensionRegistry();
        assertEquals(DimensionRegistry.OVERWORLD_DEFAULT, registry.dimensionInfo(5));
    }

    @Test
    void chybejiciDataDajiDefault() {
        DimensionRegistry registry = new DimensionRegistry();
        registry.accept(Key.key("minecraft:dimension_type"), List.of(
                new RegistryEntry(Key.key("minecraft:overworld"), null)));

        assertEquals(DimensionRegistry.OVERWORLD_DEFAULT, registry.dimensionInfo(0));
    }

    @Test
    void nevalidniVyskaDaDefault() {
        DimensionRegistry registry = new DimensionRegistry();
        registry.accept(Key.key("minecraft:dimension_type"), List.of(
                dimension("minecraft:broken", 0, 100))); // není násobek 16

        DimensionRegistry.DimensionInfo info = registry.dimensionInfo(0);
        assertEquals(DimensionRegistry.OVERWORLD_DEFAULT.minY(), info.minY());
        assertEquals(DimensionRegistry.OVERWORLD_DEFAULT.height(), info.height());
        // Klíč typu se zachovává i při rozbitých rozměrech – rozbitá výška
        // u the_end nesmí z dimenze udělat overworld.
        assertEquals("minecraft:broken", info.typeKey());
    }

    @Test
    void pocetBiomuUrciBityPalety() {
        DimensionRegistry registry = new DimensionRegistry();
        registry.accept(Key.key("minecraft:worldgen/biome"), List.of(
                new RegistryEntry(Key.key("minecraft:plains"), null),
                new RegistryEntry(Key.key("minecraft:desert"), null),
                new RegistryEntry(Key.key("minecraft:forest"), null)));

        assertEquals(2, registry.biomeBits()); // 3 biomy → 2 bity
    }

    @Test
    void ceilLog2() {
        assertEquals(0, DimensionRegistry.ceilLog2(1));
        assertEquals(1, DimensionRegistry.ceilLog2(2));
        assertEquals(2, DimensionRegistry.ceilLog2(3));
        assertEquals(15, DimensionRegistry.ceilLog2(26_000));
    }

    @Test
    void enchantovyRegistrMapujeIdNaKlice() {
        DimensionRegistry registry = new DimensionRegistry();
        assertNull(registry.enchantmentKey(0), "před registry daty nic");
        registry.accept(Key.key("minecraft:enchantment"), List.of(
                new RegistryEntry(Key.key("minecraft:protection"), null),
                new RegistryEntry(Key.key("minecraft:sharpness"), null)));
        assertEquals("protection", registry.enchantmentKey(0));
        assertEquals("sharpness", registry.enchantmentKey(1));
        assertNull(registry.enchantmentKey(2), "mimo rozsah");
        assertNull(registry.enchantmentKey(-1));
    }
}
