package dev.botalive.core.build.plan;

import dev.botalive.core.settlement.SettlementTier;

/**
 * Jednotný zdroj pravdy pro „prosperita sídla + osobnost → rozměry stavby".
 * Čisté, jednotkově testovatelné funkce (jako {@link HomeUpgrade#next}); veškerá
 * logika „jak velká má stavba být" žije tady, ať ji nikdo nepočítá ad hoc.
 *
 * <p>Vzor rámce „stavba jako proces": <b>prosperita je hlavní hnací síla</b>
 * (osada staví útulně, vesnice větší, město honosně), <b>osobnost moduluje</b>
 * (pracovitý zvelebuje, líný bydlí skromně) a <b>konfigurace dává strop</b>.
 * Stejné pravidlo pak platí pro první stavbu i pro pozdější růst (stavba se
 * zvětšuje, jak sídlo bohatne) – růst je monotónní, hlídají ho volající
 * ({@code max()} proti postavené velikosti).</p>
 */
public final class StructureSizer {

    private StructureSizer() {
    }

    // ------------------------------------------------------------------- domy

    /**
     * Šířka (= hloubka, dům je čtvercový) půdorysu domu ze stupně sídla a lenosti:
     * osada útulné 5×5, vesnice větší, město do stropu; líný staví malý bez ohledu
     * na sídlo. Půdorys je vždy lichý (čistý střed pro stavitele) a ≥ 5.
     *
     * @param tier     stupeň sídla ({@code null} = osada / bez sídla)
     * @param laziness lenost bota (0–1)
     * @param cap      strop půdorysu z konfigurace (lichý, ≥ 5)
     * @return šířka půdorysu (lichá, 5..cap)
     */
    public static int houseWidth(SettlementTier tier, double laziness, int cap) {
        int base = switch (tier == null ? SettlementTier.OSADA : tier) {
            case OSADA -> 5;
            case VESNICE -> Math.min(cap, 7);
            case MESTO -> cap;
        };
        if (laziness > 0.66) {
            base = 5; // líný bydlí skromně
        }
        int width = Math.min(base, cap);
        if (width % 2 == 0) {
            width--; // lichý půdorys
        }
        return Math.max(5, width);
    }

    /**
     * Výška zdí domu ze stupně sídla a lenosti: osada staví nízko ({@code
     * minHeight}), vesnice o patro víc, město ještě výš (do {@code maxHeight}).
     * Osobnost posune o stupeň (pracovitý výš, líný níž). Vyšší zdi = vzdušnější
     * dům; geometrie {@link HouseGenerator} zvládne libovolnou výšku ≥ 2 (větší
     * dostaví z pilířového stanoviště).
     *
     * @param tier      stupeň sídla ({@code null} = osada / bez sídla)
     * @param laziness  lenost bota (0–1)
     * @param minHeight spodní mez (konfigurace {@code build.wall-height})
     * @param maxHeight horní mez (konfigurace {@code build.max-wall-height})
     * @return výška zdí (2..maxHeight)
     */
    public static int houseWallHeight(SettlementTier tier, double laziness,
                                      int minHeight, int maxHeight) {
        int base = minHeight + switch (tier == null ? SettlementTier.OSADA : tier) {
            case OSADA -> 0;
            case VESNICE -> 1;
            case MESTO -> 2;
        };
        if (laziness < 0.34) {
            base += 1; // pracovitý staví vzdušněji
        } else if (laziness > 0.66) {
            base -= 1; // líný bydlí skromně
        }
        return Math.max(2, Math.min(maxHeight, base));
    }

    /**
     * Stavební stupeň (materiál) domu z prosperity sídla a osobnosti: osada srub,
     * vesnice solidní, město reprezentativní; osobnost posune o stupeň. Geometrie
     * na tieru nezávisí – tier je jen paleta.
     *
     * @param tier     stupeň sídla ({@code null} = osada / bez sídla)
     * @param laziness lenost bota (0–1)
     * @return stavební stupeň domu
     */
    public static BuildTier houseTier(SettlementTier tier, double laziness) {
        int base = switch (tier == null ? SettlementTier.OSADA : tier) {
            case OSADA -> 0;   // srub
            case VESNICE -> 1; // solidní
            case MESTO -> 2;   // reprezentativní
        };
        if (laziness < 0.34) {
            base += 1; // pracovitý/hrdý zvelebuje
        } else if (laziness > 0.66) {
            base -= 1; // líný bydlí skromně
        }
        return BuildTier.fromOrdinal(base);
    }

    /**
     * Celý rozměr domu (čtvercový půdorys + výška) pro daný stupeň sídla a lenost.
     *
     * @param tier      stupeň sídla ({@code null} = osada / bez sídla)
     * @param laziness  lenost bota (0–1)
     * @param widthCap  strop půdorysu ({@code build.width})
     * @param minHeight spodní mez výšky ({@code build.wall-height})
     * @param maxHeight horní mez výšky ({@code build.max-wall-height})
     * @return rozměr domu (šířka == hloubka, lichá)
     */
    public static StructureSize house(SettlementTier tier, double laziness,
                                      int widthCap, int minHeight, int maxHeight) {
        int width = houseWidth(tier, laziness, widthCap);
        int height = houseWallHeight(tier, laziness, minHeight, maxHeight);
        return new StructureSize(width, width, height);
    }
}
