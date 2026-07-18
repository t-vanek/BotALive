package dev.botalive.core.build;

/**
 * Orientace domku – světová strana, na kterou míří dveře.
 *
 * <p>Domky ve vesnici se natáčejí dveřmi k návsi; {@link #NORTH} odpovídá
 * původní pevné geometrii {@link HouseBlueprint} (dveřní otvor na hraně
 * s minimálním z).</p>
 */
public enum HouseFacing {

    /** Dveře míří na −z. */
    NORTH(0, -1),
    /** Dveře míří na +z. */
    SOUTH(0, 1),
    /** Dveře míří na −x. */
    WEST(-1, 0),
    /** Dveře míří na +x. */
    EAST(1, 0);

    private final int dx;
    private final int dz;

    HouseFacing(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    /** @return jednotkový směr dveří v ose x */
    public int dx() {
        return dx;
    }

    /** @return jednotkový směr dveří v ose z */
    public int dz() {
        return dz;
    }

    /**
     * Orientace „z bodu k bodu" podle dominantní osy – dům na parcele se
     * touto orientací dívá dveřmi směrem k návsi.
     *
     * @param fromX odkud x (střed domu)
     * @param fromZ odkud z
     * @param toX   kam x (střed vesnice)
     * @param toZ   kam z
     * @return orientace dveří směrem k cíli (při shodě vzdáleností osa x)
     */
    public static HouseFacing toward(int fromX, int fromZ, int toX, int toZ) {
        int dx = toX - fromX;
        int dz = toZ - fromZ;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? EAST : WEST;
        }
        return dz >= 0 ? SOUTH : NORTH;
    }
}
