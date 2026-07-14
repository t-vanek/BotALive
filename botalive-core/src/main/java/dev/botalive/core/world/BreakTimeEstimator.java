package dev.botalive.core.world;

/**
 * Klientský odhad doby těžby bloku (vanilla vzorec) – pro paketový režim,
 * kde server-side {@code Block.getBreakSpeed(Player)} není k dispozici.
 *
 * <p>Vanilla: poškození bloku za tick = {@code rychlost / tvrdost / dělitel},
 * kde dělitel je 30 (blok jde sklidit aktuálním nástrojem/rukou) nebo 100
 * (nejde – kámen rukou). Rychlost dává tier nástroje, pokud odpovídá třídě
 * bloku. Enchanty a efekty odhad ignoruje – je to tempo kopání, ne pravidlo;
 * {@code MineBlockTask} beztak ověřuje reálné zmizení bloku.</p>
 */
public final class BreakTimeEstimator {

    /** Rychlosti podle tieru nástroje ({@code InventoryHelper.toolTier}). */
    private static final double[] TIER_SPEED = {
            1.0,  // 0 – ruka / špatný nástroj
            2.0,  // 1 – dřevo
            12.0, // 2 – zlato
            4.0,  // 3 – kámen
            6.0,  // 4 – železo
            8.0,  // 5 – diamant
            9.0   // 6 – netherit
    };

    private BreakTimeEstimator() {
    }

    /**
     * Odhadne dobu těžby v ticích.
     *
     * @param hardness    tvrdost bloku ({@code Material.getHardness()};
     *                    &lt; 0 = nerozbitelné)
     * @param correctTool drží bot nástroj správné třídy pro blok?
     * @param toolTier    tier drženého nástroje (0 = ruka), použije se jen
     *                    při {@code correctTool}
     * @param harvestable jde blok sklidit tím, co bot drží (kámen rukou ne)
     * @return počet ticků do rozbití (1 = instant, 6000 = prakticky nikdy)
     */
    public static int estimateTicks(double hardness, boolean correctTool, int toolTier,
                                    boolean harvestable) {
        if (hardness < 0) {
            return 6000; // bedrock a spol.
        }
        if (hardness == 0) {
            return 1;    // instant-break (tráva, pochodně)
        }
        double speed = correctTool
                ? TIER_SPEED[Math.max(0, Math.min(TIER_SPEED.length - 1, toolTier))]
                : 1.0;
        double damage = speed / hardness / (harvestable ? 30.0 : 100.0);
        if (damage >= 1.0) {
            return 1;
        }
        return (int) Math.ceil(1.0 / damage);
    }
}
