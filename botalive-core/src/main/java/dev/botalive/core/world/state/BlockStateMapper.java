package dev.botalive.core.world.state;

import dev.botalive.core.world.BlockTraits;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Mapování síťových block-state ID (globální paleta protokolu) na materiály
 * a vlastnosti bloků.
 *
 * <p>Potřebné pro klientský world model ({@code PacketWorldView}) – chunk
 * pakety nesou jen číselné stavy bloků. Implementace musí být thread-safe
 * a rychlé (volá se z vnitřní smyčky pathfindingu).</p>
 */
public interface BlockStateMapper {

    /**
     * @param stateId síťové block-state id
     * @return vlastnosti bloku; {@link BlockTraits#UNKNOWN} pro neznámé id
     */
    BlockTraits traitsOf(int stateId);

    /**
     * @param stateId síťové block-state id
     * @return materiál bloku, nebo {@code null} pokud není známý
     */
    Material materialOf(int stateId);

    /**
     * @param stateId síťové block-state id
     * @return plná block data (včetně vlastností jako zralost plodiny),
     *         nebo {@code null} pokud nejsou známá
     */
    BlockData blockDataOf(int stateId);

    /**
     * @return počet block states v globální paletě (pro výpočet bitů palety)
     */
    int stateCount();
}
