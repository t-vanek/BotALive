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
 * @param maxLadders    strop žebříkových příček na jednu cestu (výstup na
 *                      stěnu vyšší než skok); 0 = žebříky vypnuté. Odvozuje
 *                      se z počtu žebříků v inventáři
 * @param crawl         povolit průchod jednoblokovými mezerami plazením
 *                      (strop v úrovni 1,0 nad podlahou) s cenovou přirážkou –
 *                      EXPERIMENTÁLNÍ, aktivní jen s {@code ai.crawling}
 */
public record PathOptions(boolean digThrough, int maxPlacements, int maxLadders, boolean crawl) {

    /** Čistě pěší plánování (výchozí). */
    public static final PathOptions WALK_ONLY = new PathOptions(false, 0, 0, false);

    /** Plánování s kopáním bez pokládání (kompat zkratka pro testy). */
    public static final PathOptions WITH_DIGGING = new PathOptions(true, 0, 0, false);

    /** Čistě pěší plánování s povoleným plazením mezerami (pro testy). */
    public static final PathOptions WALK_WITH_CRAWL = new PathOptions(false, 0, 0, true);

    /**
     * @param placements strop položených bloků (z inventáře bota)
     * @return akční plánování – kopání a pokládání, bez žebříků
     */
    public static PathOptions withActions(int placements) {
        return new PathOptions(true, Math.max(0, placements), 0, false);
    }

    /**
     * @param placements strop položených bloků (z inventáře bota)
     * @param ladders    strop žebříkových příček (z inventáře bota)
     * @return plné akční plánování – kopání, pokládání i žebříky
     */
    public static PathOptions withActions(int placements, int ladders) {
        return new PathOptions(true, Math.max(0, placements), Math.max(0, ladders), false);
    }

    /**
     * @return kopie s daným povolením plazení jednoblokovými mezerami
     * @param allow povolit plazivé hrany
     */
    public PathOptions withCrawl(boolean allow) {
        return new PathOptions(digThrough, maxPlacements, maxLadders, allow);
    }
}
