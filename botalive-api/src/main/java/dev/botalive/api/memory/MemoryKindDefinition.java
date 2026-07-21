package dev.botalive.api.memory;

import java.util.Objects;

/**
 * Definice kategorie vzpomínek dodané cizím pluginem (viz {@link MemoryKindRegistry}).
 *
 * <p>Umožňuje pluginu mít vlastní druhy vzpomínek s vlastním časovým rozpadem –
 * stejný mechanismus, jaký jádro používá pro slábnutí přátelství/zášti. Bez
 * registrace lze cizí kategorii používat také (přes řetězcové varianty
 * {@link BotMemory}), jen se nerozpadá.</p>
 *
 * @param id              stabilní id kategorie (např. {@code "myplugin:shrine"});
 *                        nesmí kolidovat s vestavěným {@link MemoryKind}
 * @param dailyDecay      o kolik denně slábne důležitost neoživované vzpomínky
 *                        ({@code 0} = bez rozpadu)
 * @param importanceFloor podlaha – rozpadem důležitost nikdy neklesne pod ni
 */
public record MemoryKindDefinition(String id, double dailyDecay, double importanceFloor) {

    private static final double DAY_MS = 24.0 * 60 * 60 * 1000;

    /** Validace a normalizace id. */
    public MemoryKindDefinition {
        Objects.requireNonNull(id, "id");
        id = id.trim();
        if (id.isBlank()) {
            throw new IllegalArgumentException("id kategorie nesmí být prázdné");
        }
    }

    /**
     * Efektivní důležitost po časovém rozpadu (počítá se při čtení, nic se
     * nepřepisuje – stejný vzor jako u vestavěných vztahů).
     *
     * @param importance uložená důležitost (platná k času {@code updatedAt})
     * @param updatedAt  čas posledního oživení (epoch ms)
     * @param now        aktuální čas (epoch ms)
     * @return důležitost po rozpadu
     */
    public double effective(double importance, long updatedAt, long now) {
        if (dailyDecay <= 0 || now <= updatedAt) {
            return importance;
        }
        double decayed = importance - dailyDecay * ((now - updatedAt) / DAY_MS);
        return Math.max(Math.min(importance, importanceFloor), decayed);
    }
}
