package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;

import java.util.List;

/**
 * Zásah do terénu naplánovaný jako součást cesty: bloky, které je potřeba
 * vykopat či položit, než bot vkročí na příslušný waypoint.
 *
 * <p>Dřív byl každý zásah reaktivní: bot došel k překážce, zasekl se
 * (2,5 s), replánoval, eskaloval k assistu, vykopl/položil 1–2 bloky a celý
 * cyklus (s plným A*) se opakoval – dlouhý tunel ani most tak nešly vůbec.
 * S akcemi v plánu je tunel či most jedna souvislá cesta: bot jde, kope
 * a pokládá podle plánu a cesta pokračuje bez replánů.</p>
 *
 * @param digs   bloky k vykopání v pořadí (shora dolů – strop dřív než podlaha)
 * @param places bloky k položení v pořadí (opora mostu, pilíř pod nohama)
 * @param ladder žebříkový výstup na stěnu ({@code null} = žádný) – exekuce
 *               běží přes {@code LadderTask} (sloupec příček z footholdu
 *               a jeden plynulý výstup), plán nese jen směr a výšku
 */
public record TerrainAction(List<BlockPos> digs, List<BlockPos> places, Ladder ladder) {

    /**
     * Žebříkový výstup: stěna v kardinálním směru {@code (sx, sz)} od bota,
     * {@code height} příček (= výška stěny).
     */
    public record Ladder(int sx, int sz, int height) {
    }

    /**
     * Čistě kopací zásah.
     *
     * @param digs bloky k vykopání
     */
    public TerrainAction(List<BlockPos> digs) {
        this(digs, List.of(), null);
    }

    /**
     * Kopací a pokládací zásah bez žebříku.
     *
     * @param digs   bloky k vykopání
     * @param places bloky k položení
     */
    public TerrainAction(List<BlockPos> digs, List<BlockPos> places) {
        this(digs, places, null);
    }

    /**
     * @param sx     krok ke stěně po ose X (-1/0/1)
     * @param sz     krok ke stěně po ose Z (-1/0/1)
     * @param height počet příček (výška stěny)
     * @return čistě žebříkový zásah
     */
    public static TerrainAction ladderClimb(int sx, int sz, int height) {
        return new TerrainAction(List.of(), List.of(), new Ladder(sx, sz, height));
    }
}
