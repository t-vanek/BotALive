package dev.botalive.core.vehicle;

import dev.botalive.core.inventory.Items;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

/**
 * Sdílené čisté pomůcky kolem lodí – rozpoznání lodního itemu/entity a měření
 * souvislé vodní plochy ve směru cesty.
 *
 * <p>Používá je jak rekreační {@link dev.botalive.core.ai.goals.BoatRideGoal},
 * tak cílená {@link dev.botalive.core.tasks.WaterCrossTask}. Vše je bezstavové
 * a testovatelné nad syntetickým {@link WorldView}.</p>
 */
public final class Boats {

    private Boats() {
    }

    /**
     * Nejmenší šířka souvislé vody (bloky) ve směru cíle, kdy se vyplatí
     * nasednout do lodi místo pomalého plavání. Kratší vodu bot přeplave.
     */
    public static final int MIN_CROSS_WIDTH = 8;

    /** Jak daleko dopředu měřit vodu/břeh při rozhodování o lodi. */
    public static final int SCAN_MAX = 48;

    /** Kolik bloků břehu se toleruje, než ve směru cíle začne voda. */
    private static final int LEAD_GAP = 3;

    /**
     * @param material materiál v inventáři (může být {@code null})
     * @return {@code true} pro lodní item (loď i loď s truhlou, prám)
     */
    public static boolean isBoatItem(Material material) {
        return Items.isBoat(material);
    }

    /**
     * @param typeName název typu entity (mcprotocollib {@code EntityType.name()})
     * @return {@code true} pro loď/prám (i variantu s truhlou)
     */
    public static boolean isBoatType(String typeName) {
        return typeName.endsWith("_BOAT") || typeName.endsWith("_RAFT");
    }

    /**
     * Vodní sloupec, po kterém může plout loď: hladina v dané pozici, nebo voda
     * o blok níž s volným prostorem nad ní (bot/loď na hladině). Láva se nepočítá.
     *
     * @param world pohled na svět
     * @param pos   testovaná pozice (úroveň nohou)
     * @return {@code true} pokud je tu splavná voda
     */
    public static boolean isWaterColumn(WorldView world, BlockPos pos) {
        BlockTraits at = world.traitsAt(pos);
        if (at.liquid() && !at.hazard()) {
            return true;
        }
        BlockTraits below = world.traitsAt(pos.down());
        return !at.solid() && below.liquid() && !below.hazard();
    }

    /**
     * Šířka souvislé vody ve směru {@code (sx,sz)} z úrovně nohou bota.
     *
     * <p>Toleruje pár bloků břehu na začátku (bot ještě nestojí ve vodě), pak
     * počítá souvislý vodní úsek až k pevnině. Když voda ve směru cíle vůbec
     * není, vrací 0.</p>
     *
     * @param world pohled na svět
     * @param feet  pozice nohou bota
     * @param sx    krok po ose X (-1/0/1)
     * @param sz    krok po ose Z (-1/0/1)
     * @return počet souvislých vodních bloků (strop {@link #SCAN_MAX})
     */
    public static int openWaterWidth(WorldView world, BlockPos feet, int sx, int sz) {
        int width = 0;
        int leadGap = 0;
        for (int i = 0; i <= SCAN_MAX; i++) {
            BlockPos p = feet.offset(sx * i, 0, sz * i);
            if (isWaterColumn(world, p)) {
                width++;
            } else if (width == 0) {
                if (++leadGap > LEAD_GAP) {
                    break; // voda ve směru cíle poblíž není
                }
            } else {
                break; // dorazili jsme na druhý břeh
            }
        }
        return width;
    }

    /**
     * Nejbližší vodní blok v okolí s volným prostorem nad hladinou – místo,
     * kam lze položit loď z inventáře.
     *
     * @param world  pohled na svět
     * @param feet   pozice nohou bota
     * @param radius vodorovný poloměr hledání
     * @return pozice hladiny, nebo {@code null} když poblíž není vhodná voda
     */
    public static BlockPos nearestWater(WorldView world, BlockPos feet, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 0; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    BlockTraits traits = world.traitsAt(pos);
                    if (traits.liquid() && !traits.hazard()
                            && world.traitsAt(pos.up()).passable()) {
                        double dist = pos.distanceSquared(feet);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = pos;
                        }
                    }
                }
            }
        }
        return best;
    }
}
