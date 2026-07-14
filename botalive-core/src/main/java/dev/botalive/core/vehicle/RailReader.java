package dev.botalive.core.vehicle;

import dev.botalive.core.util.BlockPos;

/**
 * Zdroj informací o kolejích pro simulaci minecartu.
 *
 * <p>Abstrakce odděluje kolejovou fyziku od Bukkit API: produkční implementace
 * ({@link WorldRailReader}) čte block data z chunk snapshotů, testy si koleje
 * definují přímo.</p>
 */
public interface RailReader {

    /**
     * @param pos pozice bloku
     * @return popis koleje, nebo {@code null} pokud na pozici kolej není
     */
    RailInfo railAt(BlockPos pos);
}
