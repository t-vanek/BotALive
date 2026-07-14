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

    /** Výška hráčova hitboxu. */
    public static final double PLAYER_HEIGHT = 1.8;

    /**
     * Vytvoří hitbox hráče stojícího na pozici (feet position).
     *
     * @param feet pozice nohou (střed podstavy)
     * @return AABB hráče
     */
    public static AABB playerAt(Vec3 feet) {
        double half = PLAYER_WIDTH / 2;
        return new AABB(feet.x() - half, feet.y(), feet.z() - half,
                feet.x() + half, feet.y() + PLAYER_HEIGHT, feet.z() + half);
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
