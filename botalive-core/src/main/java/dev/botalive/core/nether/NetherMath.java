package dev.botalive.core.nether;

import dev.botalive.core.util.BlockPos;

/**
 * Přepočet souřadnic mezi overworldem a Netherem (1 blok Netheru = 8 bloků
 * overworldu). Boti tu znalost používají jako hráči: když v Netheru ztratí
 * portál domů, zamíří k pozici odpovídající domovu vydělené osmi a portál
 * hledají (nebo staví) tam.
 */
public final class NetherMath {

    /** Poměr vzdáleností overworld : Nether. */
    public static final int SCALE = 8;

    /** Nejnižší rozumná cestovní výška v Netheru (nad lávovým oceánem y=31). */
    public static final int NETHER_TRAVEL_MIN_Y = 40;

    /** Nejvyšší rozumná cestovní výška v Netheru (pod stropem bedrocku y=127). */
    public static final int NETHER_TRAVEL_MAX_Y = 110;

    private NetherMath() {
    }

    /**
     * Overworld pozice → odpovídající místo v Netheru. X/Z se dělí osmi
     * (celočíselně k záporném nekonečnu, aby −15 → −2 a ne −1), Y se jen
     * ořízne do cestovního pásma Netheru – vanilla portály Y neškálují.
     *
     * @param overworld pozice v overworldu
     * @return odpovídající pozice v Netheru
     */
    public static BlockPos toNether(BlockPos overworld) {
        return new BlockPos(
                Math.floorDiv(overworld.x(), SCALE),
                clampNetherY(overworld.y()),
                Math.floorDiv(overworld.z(), SCALE));
    }

    /**
     * Nether pozice → odpovídající místo v overworldu (X/Z krát osm).
     *
     * @param nether pozice v Netheru
     * @return odpovídající pozice v overworldu
     */
    public static BlockPos toOverworld(BlockPos nether) {
        return new BlockPos(nether.x() * SCALE, nether.y(), nether.z() * SCALE);
    }

    /**
     * @param y libovolná výška
     * @return výška oříznutá do bezpečného cestovního pásma Netheru
     */
    public static int clampNetherY(int y) {
        return Math.max(NETHER_TRAVEL_MIN_Y, Math.min(NETHER_TRAVEL_MAX_Y, y));
    }
}
