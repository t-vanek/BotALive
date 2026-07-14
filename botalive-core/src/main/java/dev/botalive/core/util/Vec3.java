package dev.botalive.core.util;

/**
 * Nemutabilní 3D vektor (double přesnost) pro pozice a rychlosti botů.
 *
 * <p>Vlastní typ (místo Bukkit {@code Vector}) drží fyziku a pathfinding nezávislé
 * na Bukkit API a bezpečné pro použití mimo hlavní vlákno.</p>
 *
 * @param x složka X
 * @param y složka Y
 * @param z složka Z
 */
public record Vec3(double x, double y, double z) {

    /** Nulový vektor. */
    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    /** @return součet s vektorem */
    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    /** @return součet se složkami */
    public Vec3 add(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    /** @return rozdíl (this - o) */
    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    /** @return vektor vynásobený skalárem */
    public Vec3 mul(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    /** @return vektor s po složkách vynásobenými hodnotami */
    public Vec3 mul(double sx, double sy, double sz) {
        return new Vec3(x * sx, y * sy, z * sz);
    }

    /** @return délka vektoru */
    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** @return délka vodorovné složky (XZ) */
    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    /** @return druhá mocnina vzdálenosti k bodu */
    public double distanceSquared(Vec3 o) {
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** @return vzdálenost k bodu */
    public double distance(Vec3 o) {
        return Math.sqrt(distanceSquared(o));
    }

    /** @return normalizovaný vektor (nulový vektor zůstává nulový) */
    public Vec3 normalized() {
        double len = length();
        return len < 1.0E-8 ? ZERO : new Vec3(x / len, y / len, z / len);
    }

    /** @return vektor s vynulovanou složkou Y */
    public Vec3 horizontal() {
        return new Vec3(x, 0, z);
    }

    /** @return pozice bloku, ve kterém bod leží */
    public BlockPos toBlockPos() {
        return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }
}
