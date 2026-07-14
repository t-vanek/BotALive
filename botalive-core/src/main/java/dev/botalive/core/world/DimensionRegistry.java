package dev.botalive.core.world;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry dimenzí a biomů získané z konfigurační fáze protokolu.
 *
 * <p>Server posílá v konfigurační fázi {@code ClientboundRegistryDataPacket}
 * s obsahem registrů. Odsud bereme:</p>
 * <ul>
 *   <li>{@code minecraft:dimension_type} – {@code min_y} a {@code height}
 *       každé dimenze (login/respawn pak odkazuje indexem do tohoto seznamu);
 *       bez toho nelze určit počet chunk sekcí,</li>
 *   <li>{@code minecraft:worldgen/biome} – počet biomů (bity biome palety).</li>
 * </ul>
 *
 * <p>Aby server poslal plná data (a ne odkazy na datapacky, které „klient
 * zná"), posílá spojení v packet režimu prázdnou known-packs odpověď –
 * viz {@code BotConnection}.</p>
 */
public final class DimensionRegistry {

    /** Výchozí rozměry overworldu (fallback při chybějících datech). */
    public static final DimensionInfo OVERWORLD_DEFAULT = new DimensionInfo(-64, 384);

    /**
     * Rozměry jedné dimenze.
     *
     * @param minY   nejnižší stavební výška
     * @param height celková výška světa (počet bloků)
     */
    public record DimensionInfo(int minY, int height) {

        /** @return počet 16bloků vysokých sekcí */
        public int sectionCount() {
            return height >> 4;
        }
    }

    private final List<DimensionInfo> dimensions = new CopyOnWriteArrayList<>();
    private volatile int biomeCount = 64;

    /**
     * Zpracuje registry paket (volá se ze síťového vlákna v konfigurační fázi).
     *
     * @param registry klíč registru
     * @param entries  položky registru
     */
    public void accept(Key registry, List<RegistryEntry> entries) {
        switch (registry.asString()) {
            case "minecraft:dimension_type" -> {
                dimensions.clear();
                for (RegistryEntry entry : entries) {
                    dimensions.add(parseDimension(entry.getData()));
                }
            }
            case "minecraft:worldgen/biome" -> biomeCount = Math.max(1, entries.size());
            default -> {
                // ostatní registry (chat typy, trim materiály, ...) nepotřebujeme
            }
        }
    }

    private static DimensionInfo parseDimension(NbtMap data) {
        if (data == null) {
            return OVERWORLD_DEFAULT;
        }
        int minY = data.containsKey("min_y") ? data.getInt("min_y") : OVERWORLD_DEFAULT.minY();
        int height = data.containsKey("height") ? data.getInt("height") : OVERWORLD_DEFAULT.height();
        // Sanity: výška musí být kladný násobek 16.
        if (height <= 0 || (height & 15) != 0) {
            return OVERWORLD_DEFAULT;
        }
        return new DimensionInfo(minY, height);
    }

    /**
     * @param index index dimenze z login/respawn paketu
     * @return rozměry dimenze (fallback overworld při neznámém indexu)
     */
    public DimensionInfo dimensionInfo(int index) {
        if (index < 0 || index >= dimensions.size()) {
            return OVERWORLD_DEFAULT;
        }
        return dimensions.get(index);
    }

    /** @return počet známých dimenzí */
    public int dimensionCount() {
        return dimensions.size();
    }

    /** @return bity biome palety (ceil(log2(počet biomů))) */
    public int biomeBits() {
        return ceilLog2(biomeCount);
    }

    /**
     * @param value kladné číslo
     * @return nejmenší n takové, že 2^n >= value
     */
    public static int ceilLog2(int value) {
        return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
    }
}
