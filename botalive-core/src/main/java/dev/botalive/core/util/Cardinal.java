package dev.botalive.core.util;

/**
 * Světové strany v rovině XZ – jediná definice pro celý plugin.
 *
 * <p>Používají ji koleje (směr jízdy minecartu, {@code RailInfo}) i domy
 * (orientace dveří, {@code HouseBlueprint}); dvě nezávislé enum kopie by
 * driftovaly – yaw a směrové vektory patří k sobě.</p>
 */
public enum Cardinal {

    /** −Z. */
    NORTH(0, -1),
    /** +Z. */
    SOUTH(0, 1),
    /** +X. */
    EAST(1, 0),
    /** −X. */
    WEST(-1, 0);

    private final int dx;
    private final int dz;

    Cardinal(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    /** @return posun po ose X */
    public int dx() {
        return dx;
    }

    /** @return posun po ose Z */
    public int dz() {
        return dz;
    }

    /** @return opačný směr */
    public Cardinal opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
        };
    }

    /** @return yaw odpovídající směru (pohled/jízda tímto směrem) */
    public float yaw() {
        return switch (this) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
        };
    }

    /**
     * Směr „z bodu k bodu" podle dominantní osy – dům na parcele se touto
     * orientací dívá dveřmi k návsi.
     *
     * @param fromX odkud x
     * @param fromZ odkud z
     * @param toX   kam x
     * @param toZ   kam z
     * @return směr k cíli (při shodě vzdáleností vyhrává osa x)
     */
    public static Cardinal toward(int fromX, int fromZ, int toX, int toZ) {
        int dx = toX - fromX;
        int dz = toZ - fromZ;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? EAST : WEST;
        }
        return dz >= 0 ? SOUTH : NORTH;
    }
}
