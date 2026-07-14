package dev.botalive.core.pathfinding;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy A* pathfinderu nad syntetickým světem.
 */
class AStarPathfinderTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    @Test
    void najdeCestuPoRovnePodlaze() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 6), 0);

        assertTrue(path.complete(), "cesta má dosáhnout cíle");
        assertFalse(path.isEmpty());
        // Všechny waypointy musí být pochozí (pevná podlaha pod nohama).
        for (BlockPos waypoint : path.waypoints()) {
            assertTrue(world.traitsAt(waypoint.down()).solid()
                            || world.traitsAt(waypoint).liquid(),
                    "waypoint " + waypoint + " není pochozí");
        }
    }

    @Test
    void prekonaZedVyskyJedna() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Zeď výšky 1 napříč cestou (x=3, celý rozsah z).
        for (int z = -8; z <= 8; z++) {
            world.set(3, FEET, z, FakeWorldView.SOLID);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete(), "zeď výšky 1 se dá přeskočit");
        // Cesta musí obsahovat výstup na zeď (y = FEET+1).
        assertTrue(path.waypoints().stream().anyMatch(p -> p.y() == FEET + 1),
                "očekáván skok na zeď");
    }

    @Test
    void vyhneSeLave() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Pruh lávy (x=3, z −2..2) – vlevo zůstává obchozí koridor.
        for (int z = -2; z <= 2; z++) {
            world.set(3, FEET, z, FakeWorldView.HAZARD);
            world.set(3, FLOOR, z, FakeWorldView.HAZARD);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete());
        for (BlockPos waypoint : path.waypoints()) {
            assertFalse(world.traitsAt(waypoint).hazard(),
                    "cesta nesmí vést lávou: " + waypoint);
        }
    }

    @Test
    void nedosazitelnyCilVratiCastecnouCestu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Cíl obezděný ze všech stran do výšky 2 (skok nestačí).
        BlockPos goal = new BlockPos(10, FEET, 0);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    world.wall(goal.x() + dx, FEET, FEET + 2, goal.z() + dz);
                }
            }
        }
        // Strop nad cílem proti seskoku shora.
        world.set(goal.x(), FEET + 2, goal.z(), FakeWorldView.SOLID);

        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), goal, 2000);

        assertFalse(path.complete(), "obezděný cíl nesmí být dosažen");
    }
}
