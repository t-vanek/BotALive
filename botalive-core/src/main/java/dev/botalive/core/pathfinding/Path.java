package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;

import java.util.List;

/**
 * Naplánovaná cesta – posloupnost pochozích bloků od startu k cíli.
 *
 * @param waypoints  bloky, po kterých bot půjde (pozice nohou)
 * @param complete   {@code true} pokud cesta končí v cíli; {@code false} u
 *                   částečné cesty (A* vyčerpal rozpočet a vrací nejlepší přiblížení)
 */
public record Path(List<BlockPos> waypoints, boolean complete) {

    /** @return {@code true} pokud cesta neobsahuje žádný krok */
    public boolean isEmpty() {
        return waypoints.isEmpty();
    }

    /** @return cílový blok cesty, nebo {@code null} u prázdné cesty */
    public BlockPos destination() {
        return waypoints.isEmpty() ? null : waypoints.getLast();
    }
}
