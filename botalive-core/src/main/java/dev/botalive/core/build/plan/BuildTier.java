package dev.botalive.core.build.plan;

/**
 * Stavební stupeň domu – „jak je dům dodělaný", nezávisle na jeho geometrii.
 * Stejný {@link Blueprint} se staví z jiné {@link Palette} podle tieru, takže
 * povýšení domu je jen změna materiálů, ne přestavba tvaru.
 *
 * <p>Dům se rodí provizorní ({@link #PROVISIONAL}) a v čase dozrává, jak roste
 * prosperita sídla. Pořadí (ordinal) je stupeň: {@code 0 → 1 → 2}.</p>
 */
public enum BuildTier {

    /** Srub: vše ze dřeva/hlíny, okna jen otvory. Rychlé z toho, co roste okolo. */
    PROVISIONAL,
    /** Solidní: kamenný základ, prkenné zdi, zasklená okna (dnešní výchozí vzhled). */
    SOLID,
    /** Reprezentativní: cihly / tesaný kámen, tabulková okna, honosná střecha. */
    REFINED;

    /**
     * @param ordinal stupeň {@code 0..2} (mimo rozsah se přichytí k mezím)
     * @return odpovídající tier
     */
    public static BuildTier fromOrdinal(int ordinal) {
        BuildTier[] values = values();
        int clamped = Math.max(0, Math.min(values.length - 1, ordinal));
        return values[clamped];
    }
}
