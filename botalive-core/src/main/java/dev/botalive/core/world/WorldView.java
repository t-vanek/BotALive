package dev.botalive.core.world;

import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;

/**
 * Vláknově bezpečný pohled na geometrii světa pro fyziku a pathfinding.
 *
 * <p>Abstrakce má dvě možné implementace:</p>
 * <ul>
 *   <li><b>Server-side snapshoty</b> ({@link SnapshotWorldView}) – využívá faktu,
 *       že boti hrají na témže serveru, kde plugin běží. Chunk snapshoty jsou
 *       autoritativní, levné a odpadá mapování síťových block-state ID na
 *       materiály. <i>Zvolená, nejrobustnější varianta.</i></li>
 *   <li><b>Klientský model</b> – parsování chunk paketů na straně bota. Nutné,
 *       pokud by boti hráli na cizím serveru; vyžaduje vlastní registry
 *       block-state ID a je výrazně náročnější na paměť. Architektura s tímto
 *       rozhraním počítá – implementace lze zaměnit bez dopadu na AI.</li>
 * </ul>
 */
public interface WorldView {

    /**
     * @param pos pozice bloku
     * @return materiál bloku; {@code null} pokud chunk není k dispozici
     */
    Material materialAt(BlockPos pos);

    /**
     * Plná block data (stav bloku – např. zralost plodiny přes
     * {@link org.bukkit.block.data.Ageable}). Dražší než {@link #materialAt},
     * používat cíleně.
     *
     * @param pos pozice bloku
     * @return block data; {@code null} pokud chunk není k dispozici
     */
    org.bukkit.block.data.BlockData blockDataAt(BlockPos pos);

    /**
     * @param pos pozice bloku
     * @return vlastnosti bloku; {@link BlockTraits#UNKNOWN} pokud chunk není k dispozici
     */
    BlockTraits traitsAt(BlockPos pos);

    /**
     * @param pos pozice bloku
     * @return {@code true} pokud je chunk s blokem načtený v cache
     */
    boolean isAvailable(BlockPos pos);

    /**
     * Požádá o (asynchronní) načtení okolí do cache – volá se před pathfindingem.
     *
     * @param center  střed oblasti
     * @param radiusChunks poloměr v chuncích
     */
    void prefetch(BlockPos center, int radiusChunks);

    /**
     * @return název Bukkit světa, na který se pohled dívá
     */
    String worldName();
}
