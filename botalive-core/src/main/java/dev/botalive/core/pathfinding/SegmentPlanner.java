package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

/**
 * Výběr mezicílů pro navigaci na velké vzdálenosti.
 *
 * <p>A* s rozpočtem uzlů spolehlivě pokryje jednotky desítek bloků; delší
 * trasy se dělí na segmenty podél vzdušné čáry k cíli. Mezicíl je pochozí
 * bod na povrchu (sken sloupce shora dolů) v dané vzdálenosti; při
 * neprůchodnosti segmentu se zkouší laterální posun do stran – jednoduchá
 * obchůzka slepých ramen (fjord, jezero, útes).</p>
 *
 * <p>Nenačtený chunk v místě mezicíle vrací {@code null} – volající si oblast
 * přednačte (prefetch) a zkusí to znovu, případně spadne na přímý částečný
 * plán. Do neznáma se nenaviguje.</p>
 */
public final class SegmentPlanner {

    /** Svislý rozsah skenu povrchu okolo referenční výšky (bloky). */
    private static final int SURFACE_SCAN_RANGE = 24;

    private SegmentPlanner() {
    }

    /**
     * Vybere mezicíl na trase k dalekému cíli.
     *
     * @param world         pohled na svět
     * @param from          aktuální pozice bota (nohy)
     * @param to            konečný cíl
     * @param segmentLength vzdálenost mezicíle po vzdušné čáře (bloky)
     * @param lateralOffset kolmý posun od přímé čáry (bloky; ± strana)
     * @return pochozí mezicíl, nebo {@code null} když v místě není povrch
     *         (nenačtený chunk, láva) – volající zkusí jiný offset či přímý plán
     */
    public static BlockPos nextSegment(WorldView world, BlockPos from, BlockPos to,
                                       int segmentLength, int lateralOffset) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double dist = Math.hypot(dx, dz);
        if (dist < 1) {
            return to;
        }
        double ux = dx / dist;
        double uz = dz / dist;
        // Kolmice ke směru – laterální posun obchází slepá ramena.
        int x = (int) Math.round(from.x() + ux * segmentLength - uz * lateralOffset);
        int z = (int) Math.round(from.z() + uz * segmentLength + ux * lateralOffset);
        return surfaceAt(world, x, from.y(), z);
    }

    /**
     * Najde pochozí povrch ve sloupci: shora dolů první místo, kde se dá stát
     * (plný blok pod nohama nebo částečný blok v buňce) nebo plavat (hladina).
     *
     * @param world pohled na svět
     * @param x     sloupec X
     * @param refY  referenční výška (sken jde ±{@link #SURFACE_SCAN_RANGE})
     * @param z     sloupec Z
     * @return pozice nohou na povrchu, nebo {@code null} (neznámo/hazard/nic)
     */
    public static BlockPos surfaceAt(WorldView world, int x, int refY, int z) {
        boolean clearAbove = false;
        boolean clearTwoAbove = false;
        for (int y = refY + SURFACE_SCAN_RANGE; y >= refY - SURFACE_SCAN_RANGE; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            BlockTraits t = world.traitsAt(feet);
            if (t == BlockTraits.UNKNOWN) {
                return null; // studená cache / okraj světa – sem se neplánuje
            }
            if (t.hazard()) {
                return null; // láva/oheň na povrchu – ať zafunguje laterální posun
            }
            if (clearAbove && t.liquid()) {
                return feet; // vodní hladina – plavecký mezicíl
            }
            if (clearAbove && !t.web()) {
                double fh = t.floorHeight();
                if (fh >= 0.99 && fh <= 1.01 && clearTwoAbove) {
                    return feet.up(); // plný blok – stojí se na buňce nad ním
                }
                if (fh > 0 && fh < 0.99) {
                    return feet; // částečný blok (deska, sníh) – stojí se v buňce
                }
            }
            boolean bodyClear = !t.web() && !t.liquid() && t.lowProfile();
            clearTwoAbove = clearAbove;
            clearAbove = bodyClear;
        }
        return null;
    }
}
