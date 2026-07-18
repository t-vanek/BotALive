package dev.botalive.core.settlement;

import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.build.HouseFacing;
import dev.botalive.core.util.BlockPos;

/**
 * Rozložení parcel vesnice – čtvercové prstence kolem návsi, čistá geometrie.
 *
 * <p>Náves (index 0) je střed – typicky dům zakladatele. Parcely s indexem
 * ≥ 1 leží na prstencích kolem středu: prstenec {@code r} má {@code 8r} buněk
 * ve vzdálenosti {@code r × spacing}. Index tedy deterministicky určuje
 * pozici; vesnice roste od návsi ven a domy se natáčejí dveřmi ke středu.</p>
 */
public final class PlotLayout {

    private PlotLayout() {
    }

    /**
     * Origin (minimální roh půdorysu) parcely daného indexu.
     *
     * <p>Y přebírá střed vesnice – skutečnou úroveň terénu si doladí stavitel
     * (hledá pevnou zem ±2 bloky).</p>
     *
     * @param center  střed vesnice (náves)
     * @param index   index parcely (≥ 1)
     * @param spacing rozestup buněk mřížky (bloky)
     * @return origin parcely
     */
    public static BlockPos plotOrigin(BlockPos center, int index, int spacing) {
        int[] cell = cellFor(index);
        int half = HouseBlueprint.SIZE / 2;
        return new BlockPos(
                center.x() + cell[0] * spacing - half,
                center.y(),
                center.z() + cell[1] * spacing - half);
    }

    /**
     * Orientace domu na parcele – dveřmi k návsi.
     *
     * @param plotOrigin origin parcely
     * @param center     střed vesnice
     * @return orientace dveří
     */
    public static HouseFacing facingToward(BlockPos plotOrigin, BlockPos center) {
        int half = HouseBlueprint.SIZE / 2;
        return HouseFacing.toward(plotOrigin.x() + half, plotOrigin.z() + half,
                center.x(), center.z());
    }

    /**
     * Buňka mřížky (v jednotkách prstenců) pro index parcely.
     *
     * <p>Prstenec 1 má buňky 1–8, prstenec 2 buňky 9–24 atd. Pořadí obchází
     * obvod čtverce po směru hodinových ručiček od severozápadního rohu.</p>
     *
     * @param index index parcely (≥ 1)
     * @return {@code {dx, dz}} v jednotkách prstenců
     */
    static int[] cellFor(int index) {
        if (index < 1) {
            throw new IllegalArgumentException("index parcely musí být ≥ 1: " + index);
        }
        int i = index - 1;
        int ring = 1;
        while (i >= 8 * ring) {
            i -= 8 * ring;
            ring++;
        }
        int side = 2 * ring; // délka hrany v krocích
        // Horní hrana zleva doprava (bez pravého rohu), pravá shora dolů,
        // spodní zprava doleva, levá zdola nahoru.
        if (i < side) {
            return new int[]{-ring + i, -ring};
        }
        i -= side;
        if (i < side) {
            return new int[]{ring, -ring + i};
        }
        i -= side;
        if (i < side) {
            return new int[]{ring - i, ring};
        }
        i -= side;
        return new int[]{-ring, ring - i};
    }
}
