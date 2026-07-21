package dev.botalive.api.bot;

/**
 * Nemutabilní bod ve světě (souřadnice nohou bota nebo entity).
 *
 * <p>Datový typ bez závislosti na implementaci – vhodný do veřejného API.
 * Souřadnice jsou spojité (double); blokové souřadnice získáte přes
 * {@link #blockX()}/{@link #blockY()}/{@link #blockZ()} (zaokrouhlení k −∞,
 * stejně jako Minecraft mapuje pozici na blok).</p>
 *
 * @param x souřadnice X
 * @param y souřadnice Y (výška)
 * @param z souřadnice Z
 */
public record Position(double x, double y, double z) {

    /**
     * @param other druhý bod
     * @return eukleidovská vzdálenost k druhému bodu
     */
    public double distanceTo(Position other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** @return bloková souřadnice X (zaokrouhlení k −∞) */
    public int blockX() {
        return (int) Math.floor(x);
    }

    /** @return bloková souřadnice Y (zaokrouhlení k −∞) */
    public int blockY() {
        return (int) Math.floor(y);
    }

    /** @return bloková souřadnice Z (zaokrouhlení k −∞) */
    public int blockZ() {
        return (int) Math.floor(z);
    }
}
