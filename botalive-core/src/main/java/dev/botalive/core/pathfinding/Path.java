package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * Naplánovaná cesta – posloupnost pochozích bloků od startu k cíli.
 *
 * @param waypoints  bloky, po kterých bot půjde (pozice nohou)
 * @param complete   {@code true} pokud cesta končí v cíli; {@code false} u
 *                   částečné cesty (A* vyčerpal rozpočet a vrací nejlepší přiblížení)
 * @param actions    zásahy do terénu podle indexu waypointu: bloky, které je
 *                   potřeba vykopat, než bot na waypoint vkročí (prázdné
 *                   u čistě pěších cest – viz {@link PathOptions})
 */
public record Path(List<BlockPos> waypoints, boolean complete,
                   Map<Integer, TerrainAction> actions) {

    /**
     * Čistě pěší cesta bez zásahů do terénu.
     *
     * @param waypoints waypointy
     * @param complete  dosáhla cíle
     */
    public Path(List<BlockPos> waypoints, boolean complete) {
        this(waypoints, complete, Map.of());
    }

    /** @return {@code true} pokud cesta neobsahuje žádný krok */
    public boolean isEmpty() {
        return waypoints.isEmpty();
    }

    /** @return cílový blok cesty, nebo {@code null} u prázdné cesty */
    public BlockPos destination() {
        return waypoints.isEmpty() ? null : waypoints.getLast();
    }
}
