package dev.botalive.core.settlement;

import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.util.Cardinal;
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
     * Vycentruje půdorys stavby na střed parcely. {@link #plotOrigin} počítá roh
     * z rozměru domu (4×4); stavba s jiným půdorysem (studna 3×3, kostel 5×7)
     * by tak seděla mimo střed parcely. Tahle funkce posune roh tak, aby
     * <b>střed půdorysu</b> padl na nejbližší uzel mřížky parcel. Je
     * <b>idempotentní</b> – už vycentrovaný roh se nehne, takže rozestavěná
     * stavba se vždy trefí zpět do svého originu.
     *
     * @param origin     dosavadní roh půdorysu
     * @param footprintW šířka půdorysu (X)
     * @param footprintD hloubka půdorysu (Z)
     * @param center     střed vesnice (uzel mřížky)
     * @param spacing    rozestup buněk mřížky
     * @return vycentrovaný roh (Y beze změny)
     */
    public static BlockPos centerFootprint(BlockPos origin, int footprintW, int footprintD,
                                           BlockPos center, int spacing) {
        if (spacing <= 0) {
            return origin;
        }
        int gx = snapToGrid(origin.x() + footprintW / 2, center.x(), spacing) - footprintW / 2;
        int gz = snapToGrid(origin.z() + footprintD / 2, center.z(), spacing) - footprintD / 2;
        return new BlockPos(gx, origin.y(), gz);
    }

    /** Nejbližší uzel mřížky {@code origin + k×spacing} k dané souřadnici. */
    private static int snapToGrid(int coord, int origin, int spacing) {
        return origin + Math.round((float) (coord - origin) / spacing) * spacing;
    }

    /**
     * Orientace domu na parcele – dveřmi k návsi.
     *
     * @param plotOrigin origin parcely
     * @param center     střed vesnice
     * @return orientace dveří
     */
    public static Cardinal facingToward(BlockPos plotOrigin, BlockPos center) {
        int half = HouseBlueprint.SIZE / 2;
        return Cardinal.toward(plotOrigin.x() + half, plotOrigin.z() + half,
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

    /**
     * Index parcely pro buňku mřížky – inverze {@link #cellFor}. Slouží
     * vícparcelové rezervaci ({@code SettlementService.reserveSite}) k dohledání
     * indexů sousedních buněk bloku.
     *
     * @param dx buňka x (v jednotkách prstenců)
     * @param dz buňka z (v jednotkách prstenců)
     * @return index parcely (0 = náves/střed)
     */
    static int indexFor(int dx, int dz) {
        int ring = Math.max(Math.abs(dx), Math.abs(dz));
        if (ring == 0) {
            return 0; // náves
        }
        int start = 1 + 4 * ring * (ring - 1); // první index prstence
        int side = 2 * ring;
        int i;
        if (dz == -ring && dx < ring) {          // horní hrana: dx ∈ [-ring, ring-1]
            i = dx + ring;
        } else if (dx == ring && dz < ring) {    // pravá hrana: dz ∈ [-ring, ring-1]
            i = side + (dz + ring);
        } else if (dz == ring && dx > -ring) {   // spodní hrana: dx ∈ [-ring+1, ring]
            i = 2 * side + (ring - dx);
        } else {                                 // levá hrana: dx == -ring
            i = 3 * side + (ring - dz);
        }
        return start + i;
    }

    /**
     * Kolik buněk mřížky zabere půdorys dané délky podél jedné osy – strop
     * podílu s rozestupem. Stavba menší nebo rovná rozestupu se vejde do jedné
     * parcely; větší si vyžádá sousední (vícparcelová rezervace).
     *
     * @param footprint délka půdorysu (bloky) v ose
     * @param spacing   rozestup buněk mřížky (bloky)
     * @return počet buněk (≥ 1)
     */
    public static int plotSpan(int footprint, int spacing) {
        if (spacing <= 0 || footprint <= 0) {
            return 1;
        }
        return Math.max(1, (footprint + spacing - 1) / spacing);
    }
}
