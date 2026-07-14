package dev.botalive.core.testutil;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;
import java.util.Map;

/**
 * Syntetický svět pro testy pathfindingu a fyziky.
 *
 * <p>Výchozí stav: rovná pevná podlaha na {@code floorY}, nad ní vzduch.
 * Jednotlivé bloky lze přepsat pomocí {@link #set}.</p>
 */
public final class FakeWorldView implements WorldView {

    /** Pevný blok. */
    public static final BlockTraits SOLID = new BlockTraits(false, true, false, false, false, false, false);

    /** Láva (hazard). */
    public static final BlockTraits HAZARD = new BlockTraits(false, false, true, false, false, true, false);

    /** Voda. */
    public static final BlockTraits WATER = new BlockTraits(false, false, true, false, false, false, false);

    private final int floorY;
    private final Map<Long, BlockTraits> overrides = new HashMap<>();

    /**
     * @param floorY výška horní hrany podlahy (bloky na floorY jsou pevné)
     */
    public FakeWorldView(int floorY) {
        this.floorY = floorY;
    }

    /**
     * Přepíše blok.
     *
     * @param x      blok X
     * @param y      blok Y
     * @param z      blok Z
     * @param traits vlastnosti
     * @return this (řetězení)
     */
    public FakeWorldView set(int x, int y, int z, BlockTraits traits) {
        overrides.put(new BlockPos(x, y, z).asLong(), traits);
        return this;
    }

    /**
     * Postaví pevný sloupec od {@code yFrom} do {@code yTo} včetně.
     */
    public FakeWorldView wall(int x, int yFrom, int yTo, int z) {
        for (int y = yFrom; y <= yTo; y++) {
            set(x, y, z, SOLID);
        }
        return this;
    }

    @Override
    public Material materialAt(BlockPos pos) {
        return traitsAt(pos).solid() ? Material.STONE : Material.AIR;
    }

    @Override
    public BlockData blockDataAt(BlockPos pos) {
        return null;
    }

    @Override
    public BlockTraits traitsAt(BlockPos pos) {
        BlockTraits override = overrides.get(pos.asLong());
        if (override != null) {
            return override;
        }
        return pos.y() <= floorY ? SOLID : BlockTraits.AIR;
    }

    @Override
    public boolean isAvailable(BlockPos pos) {
        return true;
    }

    @Override
    public void prefetch(BlockPos center, int radiusChunks) {
        // syntetický svět je vždy „načtený"
    }

    @Override
    public String worldName() {
        return "fake";
    }
}
