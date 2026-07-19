package dev.botalive.core.pathfinding;

/**
 * Volby jednoho výpočtu cesty.
 *
 * <p>Kopací hrany ({@code digThrough}) se zapínají až jako náhrada assist
 * eskalace – když pěší plán selže a bot by beztak sáhl k terraformingu.
 * Běžné plánování tak zůstává čistě pěší a zásahy do terénu podléhají
 * stejným gate'ům jako dřív ({@code ai.terraforming} + kill-switch
 * {@code pathfinding.planned-actions}).</p>
 *
 * @param digThrough povolit hrany „prokopej se" (tunel, schod nahoru,
 *                   schod dolů) s tekutinovou pojistkou a deny-listem
 *                   materiálů
 */
public record PathOptions(boolean digThrough) {

    /** Čistě pěší plánování (výchozí). */
    public static final PathOptions WALK_ONLY = new PathOptions(false);

    /** Plánování se zásahy do terénu (náhrada assist eskalace). */
    public static final PathOptions WITH_DIGGING = new PathOptions(true);
}
