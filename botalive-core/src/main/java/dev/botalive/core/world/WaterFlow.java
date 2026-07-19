package dev.botalive.core.world;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;

import java.util.function.Function;

/**
 * Směr proudu tekutiny odvozený z gradientu hladin sousedů – zjednodušená
 * vanilla mechanika ({@code FlowingFluid.getFlow}): voda teče od zdroje
 * (hladina 1,0) k tenčím koncům (hladina 1/8) a přepadá přes hrany.
 *
 * <p>Bere lookup funkci traits, ne {@link WorldView} – pathfinding tak proud
 * počítá nad svou memo cache (nula dotazů do světa navíc), fyzika nad živým
 * světem. Bez block dat (materiálová úroveň, testové zdroje) mají všechny
 * tekutiny hladinu zdroje a proud je nulový – chování beze změny.</p>
 */
public final class WaterFlow {

    /** Kardinální směry (dx, dz). */
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private WaterFlow() {
    }

    /**
     * @param traits lookup vlastností bloku (memo cache A*, nebo živý svět)
     * @param pos    buňka tekutiny
     * @return jednotkový směr proudu, {@link Vec3#ZERO} bez proudu
     *         (ne-tekutina, zdrojová tůň); padající sloupec táhne dolů
     */
    public static Vec3 at(Function<BlockPos, BlockTraits> traits, BlockPos pos) {
        BlockTraits here = traits.apply(pos);
        int level = here.liquidLevel();
        if (level < 0) {
            return Vec3.ZERO;
        }
        if (level >= 8) {
            return new Vec3(0, -1, 0); // padající sloupec – táhne dolů
        }
        double own = height(level);
        double fx = 0;
        double fz = 0;
        for (int[] d : DIRECTIONS) {
            BlockPos neighborPos = pos.offset(d[0], 0, d[1]);
            BlockTraits neighbor = traits.apply(neighborPos);
            int neighborLevel = neighbor.liquidLevel();
            if (neighborLevel >= 0) {
                // Teče se k nižší hladině (tenčí voda, padající sloupec = 0).
                double diff = own - height(neighborLevel);
                fx += d[0] * diff;
                fz += d[1] * diff;
            } else if (neighbor.noCollision()
                    && traits.apply(neighborPos.down()).liquidLevel() >= 0) {
                // Hrana vodopádu: volný soused s vodou o patro níž – přepad.
                fx += d[0] * own;
                fz += d[1] * own;
            }
        }
        double length = Math.sqrt(fx * fx + fz * fz);
        if (length < 1.0E-6) {
            return Vec3.ZERO;
        }
        return new Vec3(fx / length, 0, fz / length);
    }

    /** Výška vodního sloupce pro hladinu (zdroj 1,0 … tenčí okraj 1/8; pád 0). */
    private static double height(int level) {
        return (8 - Math.min(level, 8)) / 8.0;
    }
}
