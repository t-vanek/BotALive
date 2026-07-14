package dev.botalive.core.util;

/**
 * Nemutabilní pozice bloku (celočíselné souřadnice).
 *
 * @param x blok X
 * @param y blok Y
 * @param z blok Z
 */
public record BlockPos(int x, int y, int z) {

    /** @return pozice posunutá o offset */
    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    /** @return pozice o blok výš */
    public BlockPos up() {
        return offset(0, 1, 0);
    }

    /** @return pozice o blok níž */
    public BlockPos down() {
        return offset(0, -1, 0);
    }

    /** @return střed bloku jako {@link Vec3} (pro navigaci) */
    public Vec3 center() {
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    /** @return druhá mocnina vzdálenosti středů bloků */
    public double distanceSquared(BlockPos o) {
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** @return manhattanská vzdálenost (levný odhad pro A*) */
    public int manhattan(BlockPos o) {
        return Math.abs(x - o.x) + Math.abs(y - o.y) + Math.abs(z - o.z);
    }

    /** @return X souřadnice chunku */
    public int chunkX() {
        return x >> 4;
    }

    /** @return Z souřadnice chunku */
    public int chunkZ() {
        return z >> 4;
    }

    /** @return kompaktní klíč pro mapy/sety (unikátní v rozsahu světa) */
    public long asLong() {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (long) (z & 0x3FFFFFF);
    }
}
