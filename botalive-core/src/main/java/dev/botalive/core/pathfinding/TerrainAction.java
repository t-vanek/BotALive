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
 */
public record TerrainAction(List<BlockPos> digs, List<BlockPos> places) {

    /**
     * Čistě kopací zásah.
     *
     * @param digs bloky k vykopání
     */
    public TerrainAction(List<BlockPos> digs) {
        this(digs, List.of());
    }
}
