package dev.botalive.core.pathfinding;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;

/**
 * Osobnostní profil cen pathfindingu – styl cesty podle povahy.
 *
 * <p>Dva boti ve stejném terénu volí různé trasy: odvážný bere sprint-skok
 * přes rokli, opatrný ji obejde a lávě se vyhne větším obloukem, líný se
 * vyhýbá šplhání a výskokům. Profil je deterministická funkce osobnosti
 * (žádný šum – kmitání tras mezi replány by vypadalo stroze) a násobí jen
 * <b>přirážky</b> nad základní cenou kroku, případně drží podlahu nad
 * spodním odhadem heuristik: pád nikdy pod 6/blok (heuristika {@code yLevel}),
 * šplh nikdy pod 0,85× (oktilová svislice 10/blok ≤ 12·0,85) – přípustnost
 * heuristik tím zůstává zaručená pro každý profil.</p>
 *
 * @param gapJump      násobič přirážky za skok přes mezeru (odvaha ji snižuje)
 * @param drop         násobič ceny seskoků za blok (opatrnost zvyšuje; ≥ 1)
 * @param hazardMargin násobič penalizace blízkosti lávy/ohně (opatrnost zvyšuje)
 * @param climb        násobič ceny výskoků a šplhání (lenost zvyšuje; ≥ 0,85)
 * @param water        násobič penalizací vody a potápění (opatrnost zvyšuje)
 */
public record PathCosts(double gapJump, double drop, double hazardMargin,
                        double climb, double water) {

    /** Neutrální profil (chování před personalizací). */
    public static final PathCosts DEFAULT = new PathCosts(1, 1, 1, 1, 1);

    /**
     * Odvodí profil cen z osobnosti bota.
     *
     * @param personality osobnost ({@code null} vrací {@link #DEFAULT})
     * @return deterministický profil cen
     */
    public static PathCosts of(Personality personality) {
        if (personality == null) {
            return DEFAULT;
        }
        double courage = personality.trait(Trait.COURAGE);
        double caution = personality.trait(Trait.CAUTION);
        double laziness = personality.trait(Trait.LAZINESS);
        return new PathCosts(
                1.5 - courage,                            // 0,5–1,5: odvážný skáče
                1 + caution * 0.8,                        // 1–1,8: opatrný neseskakuje
                0.7 + caution,                            // 0,7–1,7: odstup od lávy
                Math.max(0.85, 0.85 + laziness * 0.75),   // 0,85–1,6: líný nešplhá
                0.8 + caution * 0.7);                     // 0,8–1,5: opatrný se nebrodí
    }
}
