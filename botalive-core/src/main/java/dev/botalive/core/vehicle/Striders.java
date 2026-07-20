package dev.botalive.core.vehicle;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

/**
 * Sdílené čisté pomůcky kolem striderů – výbava jezdce a měření souvislé
 * lávové plochy ve směru cesty (lávová analogie {@link Boats}).
 *
 * <p>Používá je {@link dev.botalive.core.tasks.LavaCrossTask} a rozhodování
 * {@code BotImpl.shouldBoardStrider}. Vše je bezstavové a testovatelné nad
 * syntetickým {@link WorldView}.</p>
 */
public final class Striders {

    private Striders() {
    }

    /**
     * Nejmenší šířka souvislé lávy (bloky) ve směru cíle, kdy se vyplatí
     * osedlat stridera. Užší láva se přemostí reaktivním mostem
     * ({@code nether.lava-bridge-limit}) nebo obejde.
     */
    public static final int MIN_CROSS_WIDTH = 12;

    /** Jak daleko dopředu měřit lávu/břeh při rozhodování o jízdě. */
    public static final int SCAN_MAX = 64;

    /** Kolik bloků břehu se toleruje, než ve směru cíle začne láva. */
    private static final int LEAD_GAP = 3;

    /**
     * Lávový sloupec, po kterém umí jít strider: hladina lávy v dané pozici,
     * nebo láva o blok níž s volným prostorem nad ní.
     *
     * @param world pohled na svět
     * @param pos   testovaná pozice (úroveň nohou)
     * @return {@code true} pokud je tu pochozí láva
     */
    public static boolean isLavaColumn(WorldView world, BlockPos pos) {
        BlockTraits at = world.traitsAt(pos);
        if (at.liquid() && at.hazard()) {
            return true;
        }
        BlockTraits below = world.traitsAt(pos.down());
        return !at.solid() && below.liquid() && below.hazard();
    }

    /**
     * Šířka souvislé lávy ve směru {@code (sx,sz)} z úrovně nohou bota.
     *
     * <p>Toleruje pár bloků břehu na začátku (bot stojí na kraji), pak počítá
     * souvislý lávový úsek až k pevnině. Když láva ve směru cíle vůbec není,
     * vrací 0.</p>
     *
     * @param world pohled na svět
     * @param feet  pozice nohou bota
     * @param sx    krok po ose X (-1/0/1)
     * @param sz    krok po ose Z (-1/0/1)
     * @return počet souvislých lávových bloků (strop {@link #SCAN_MAX})
     */
    public static int openLavaWidth(WorldView world, BlockPos feet, int sx, int sz) {
        int width = 0;
        int leadGap = 0;
        for (int i = 0; i <= SCAN_MAX; i++) {
            BlockPos p = feet.offset(sx * i, 0, sz * i);
            if (isLavaColumn(world, p)) {
                width++;
            } else if (width == 0) {
                if (++leadGap > LEAD_GAP) {
                    break; // láva ve směru cíle poblíž není
                }
            } else {
                break; // dorazili jsme na druhý břeh
            }
        }
        return width;
    }

    /**
     * @param material materiál v inventáři (může být {@code null})
     * @return {@code true} pro houbu na prutu (řízení osedlaného stridera)
     */
    public static boolean isSteeringRod(Material material) {
        return material == Material.WARPED_FUNGUS_ON_A_STICK;
    }
}
