package dev.botalive.core.pathfinding;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regrese ze živého testu: bazén s obrubou v úrovni hladiny, cíl na terénu.
 */
class PoolExitTest {

    @Test
    void vylezeZBazenuNaTeren() {
        // Terén: pevno do y=63 (chůze 64). Skořepina 20..26 × y61..64,
        // kavita s vodou 21..25 × y62..64 (hladina = úroveň terénu).
        FakeWorldView world = new FakeWorldView(63);
        for (int x = 20; x <= 26; x++) {
            for (int z = 20; z <= 26; z++) {
                for (int y = 61; y <= 64; y++) {
                    world.set(x, y, z, FakeWorldView.SOLID);
                }
            }
        }
        for (int x = 21; x <= 25; x++) {
            for (int z = 21; z <= 25; z++) {
                for (int y = 62; y <= 64; y++) {
                    world.set(x, y, z, FakeWorldView.WATER);
                }
            }
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(23, 62, 23), new BlockPos(29, 64, 23), 0);

        assertTrue(path.complete(),
                "z bazénu se má dát vylézt na terén; waypointy: " + path.waypoints());
    }
}
