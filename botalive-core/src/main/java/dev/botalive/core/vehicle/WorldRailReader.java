package dev.botalive.core.vehicle;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Rail;

/**
 * Produkční {@link RailReader} – čte koleje z {@link WorldView}
 * (chunk snapshoty, thread-safe).
 */
public final class WorldRailReader implements RailReader {

    private final WorldView world;

    /**
     * @param world pohled na svět
     */
    public WorldRailReader(WorldView world) {
        this.world = world;
    }

    @Override
    public RailInfo railAt(BlockPos pos) {
        Material material = world.materialAt(pos);
        if (material == null || !material.name().endsWith("RAIL")) {
            return null;
        }
        BlockData data = world.blockDataAt(pos);
        if (!(data instanceof Rail rail)) {
            return null;
        }
        RailInfo.Shape shape;
        try {
            shape = RailInfo.Shape.valueOf(rail.getShape().name());
        } catch (IllegalArgumentException e) {
            return null; // neznámý tvar (budoucí verze) – radši nejet
        }
        boolean powered = data instanceof Powerable powerable && powerable.isPowered();
        return new RailInfo(shape, powered, material == Material.POWERED_RAIL);
    }
}
