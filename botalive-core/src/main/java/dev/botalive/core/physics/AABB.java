package dev.botalive.core.physics;

import dev.botalive.core.util.Vec3;

/**
 * Osově zarovnaný kvádr (axis-aligned bounding box) pro kolize bota se světem.
 *
 * @param minX minimum X
 * @param minY minimum Y
 * @param minZ minimum Z
 * @param maxX maximum X
 * @param maxY maximum Y
 * @param maxZ maximum Z
 */
public record AABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    /** Šířka hráčova hitboxu. */
    public static final double PLAYER_WIDTH = 0.6;

    /** Výška hráčova hitboxu (vestoje). */
    public static final double PLAYER_HEIGHT = 1.8;

    /**
     * Výška hitboxu při plazení (vanilla „swimming"/crawl pose 0.6). Umožňuje
     * proplížit se jednoblokovou mezerou (strop v úrovni 1,0 nad podlahou) –
     * tělo výšky 0,6 se vejde tam, kde stojící 1,8 hitbox naráží.
     */
    public static final double CRAWL_HEIGHT = 0.6;

    /**
     * Vytvoří hitbox hráče stojícího na pozici (feet position).
     *
     * @param feet pozice nohou (střed podstavy)
     * @return AABB hráče (plná výška vestoje)
     */
    public static AABB playerAt(Vec3 feet) {
        return playerAt(feet, PLAYER_HEIGHT);
    }

    /**
     * Vytvoří hitbox hráče dané výšky (plná výška vestoje, nebo
     * {@link #CRAWL_HEIGHT} při plazení).
     *
     * @param feet   pozice nohou (střed podstavy)
     * @param height výška hitboxu
     * @return AABB hráče
     */
    public static AABB playerAt(Vec3 feet, double height) {
        double half = PLAYER_WIDTH / 2;
        return new AABB(feet.x() - half, feet.y(), feet.z() - half,
                feet.x() + half, feet.y() + height, feet.z() + half);
    }

    /** @return box posunutý o vektor */
    public AABB move(double dx, double dy, double dz) {
        return new AABB(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** @return {@code true} pokud se boxy protínají */
    public boolean intersects(AABB o) {
        return minX < o.maxX && maxX > o.minX
                && minY < o.maxY && maxY > o.minY
                && minZ < o.maxZ && maxZ > o.minZ;
    }
}
