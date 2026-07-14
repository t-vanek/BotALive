package dev.botalive.api.memory;

import java.util.Map;
import java.util.UUID;

/**
 * Jedna vzpomínka v dlouhodobé paměti bota.
 *
 * @param id         primární klíč (0 dokud není uložena do databáze)
 * @param botId      UUID vlastníka vzpomínky
 * @param kind       kategorie vzpomínky
 * @param world      název světa, ke kterému se vzpomínka váže (může být {@code null})
 * @param x          blok X
 * @param y          blok Y
 * @param z          blok Z
 * @param subject    UUID subjektu (hráč/entita), pokud se vzpomínka týká někoho konkrétního
 * @param data       doplňková strukturovaná data (serializují se jako JSON)
 * @param importance důležitost 0–1; málo důležité vzpomínky mohou být časem zapomenuty
 * @param createdAt  epoch millis vzniku
 * @param updatedAt  epoch millis poslední aktualizace / oživení vzpomínky
 */
public record MemoryRecord(
        long id,
        UUID botId,
        MemoryKind kind,
        String world,
        int x,
        int y,
        int z,
        UUID subject,
        Map<String, String> data,
        double importance,
        long createdAt,
        long updatedAt
) {

    /**
     * @return kopie záznamu s novým primárním klíčem (po INSERTu do DB)
     */
    public MemoryRecord withId(long newId) {
        return new MemoryRecord(newId, botId, kind, world, x, y, z, subject, data, importance, createdAt, updatedAt);
    }

    /**
     * @return druhá mocnina vzdálenosti vzpomínky od zadaného bodu (rychlé porovnávání)
     */
    public double distanceSquared(int px, int py, int pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
