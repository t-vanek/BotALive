package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;

import java.util.List;

/**
 * Zásah do terénu naplánovaný jako součást cesty: bloky, které je potřeba
 * vykopat, než bot vkročí na příslušný waypoint.
 *
 * <p>Dřív byl každý zásah reaktivní: bot došel k překážce, zasekl se
 * (2,5 s), replánoval, eskaloval k assistu, vykopl 1–2 bloky a celý cyklus
 * (s plným A*) se opakoval – dlouhý tunel tak nešel vůbec (strop 10 cyklů).
 * S akcemi v plánu je tunel jedna souvislá cesta: bot jde, kope podle
 * plánu a cesta pokračuje bez replánů.</p>
 *
 * @param digs bloky k vykopání v pořadí (shora dolů – strop dřív než podlaha)
 */
public record TerrainAction(List<BlockPos> digs) {
}
