package dev.botalive.core.world.state;

import dev.botalive.core.world.BlockTraits;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Nouzové mapování block states, když se nepodaří sestavit přesnou tabulku
 * ({@link ReflectionBlockStateMapper}).
 *
 * <p>Sémantika „vzduch (state 0) je průchozí, všechno ostatní je pevné":
 * boti se dokážou bezpečně pohybovat po povrchu (pathfinding vidí terén jako
 * plné bloky a vzduch), ale nerozliší vodu, lávu, koleje ani plodiny –
 * pokročilé schopnosti (těžba, lodě, farmaření) jsou v tomto režimu
 * degradované. Do logu se to hlásí při startu.</p>
 */
public final class FallbackBlockStateMapper implements BlockStateMapper {

    /** Vanilla 26.1 má ~30k stavů; 16 bitů je bezpečný horní odhad palety. */
    private static final int ASSUMED_STATE_COUNT = 1 << 16;

    private static final BlockTraits SOLID =
            new BlockTraits(false, true, false, false, false, false, false, false, false);

    @Override
    public BlockTraits traitsOf(int stateId) {
        return stateId == 0 ? BlockTraits.AIR : SOLID;
    }

    @Override
    public Material materialOf(int stateId) {
        return stateId == 0 ? Material.AIR : null;
    }

    @Override
    public BlockData blockDataOf(int stateId) {
        return null;
    }

    @Override
    public int stateCount() {
        return ASSUMED_STATE_COUNT;
    }
}
