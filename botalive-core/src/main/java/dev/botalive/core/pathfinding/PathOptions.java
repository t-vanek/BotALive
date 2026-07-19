package dev.botalive.core.pathfinding;

/**
 * Volby jednoho výpočtu cesty.
 *
 * <p>Akční hrany se zapínají až jako náhrada assist eskalace – když pěší
 * plán selže a bot by beztak sáhl k terraformingu. Běžné plánování tak
 * zůstává čistě pěší a zásahy do terénu podléhají stejným gate'ům jako dřív
 * ({@code ai.terraforming} + kill-switch {@code pathfinding.planned-actions}).</p>
 *
 * @param digThrough    povolit hrany „prokopej se" (tunel, schod nahoru,
 *                      schod dolů) s tekutinovou pojistkou a deny-listem
 *                      materiálů
 * @param maxPlacements strop položených bloků na jednu cestu (mosty přes
 *                      propasti, pilíře); 0 = pokládání vypnuté. Odvozuje se
 *                      z inventáře bota – plán nikdy neslibuje víc bloků,
 *                      než bot má. Do tekutin se nepokládá (lávová jezera
 *                      řeší reaktivní {@code BridgeTask})
 */
public record PathOptions(boolean digThrough, int maxPlacements) {

    /** Čistě pěší plánování (výchozí). */
    public static final PathOptions WALK_ONLY = new PathOptions(false, 0);

    /** Plánování s kopáním bez pokládání (kompat zkratka pro testy). */
    public static final PathOptions WITH_DIGGING = new PathOptions(true, 0);

    /**
     * @param placements strop položených bloků (z inventáře bota)
     * @return plné akční plánování – kopání i pokládání
     */
    public static PathOptions withActions(int placements) {
        return new PathOptions(true, Math.max(0, placements));
    }
}
