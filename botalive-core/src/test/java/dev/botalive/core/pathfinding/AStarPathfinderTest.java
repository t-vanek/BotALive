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
    void vyhybaSeMistuSmrti() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BlockPos danger = new BlockPos(3, FEET, 0); // tady bot minule zemřel
        Path avoided = new AStarPathfinder(world, java.util.List.of(danger))
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(8, FEET, 0), 0);

        assertTrue(avoided.complete(), "cesta má dorazit i s obchůzkou");
        // Obchůzka: žádný waypoint v těsné blízkosti místa smrti.
        boolean tooClose = avoided.waypoints().stream()
                .anyMatch(p -> p.distanceSquared(danger) <= 2 * 2);
        assertFalse(tooClose, "cesta má místo smrti obejít: " + avoided.waypoints());
        // Bez vzpomínky vede přímo (kontrola, že penalizace je příčinou obchůzky).
        Path direct = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(8, FEET, 0), 0);
        assertTrue(direct.waypoints().stream().anyMatch(p -> p.distanceSquared(danger) <= 1),
                "bez vzpomínky se čekala přímá cesta přes dané místo");
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
    void vynoriSeZVodniJamy() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Vodní jáma 1×1 hloubky 4 (vyhloubená do podlahy, po hladinu):
        // bot na dně musí vyplavat sloupcem vzhůru.
        for (int y = FEET - 4; y <= FEET; y++) {
            world.set(3, y, 0, FakeWorldView.WATER);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(3, FEET - 4, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete(), "z vodní jámy se má dát vyplavat: " + path.waypoints());
        assertTrue(path.waypoints().stream().anyMatch(p -> p.y() == FEET),
                "cesta má vystoupat k hladině");
    }

    @Test
    void preplaveVodniPrekop() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Vodní příkop přes celou šíři (x=3..4): plave se skrz, ne okolo.
        for (int x = 3; x <= 4; x++) {
            for (int z = -8; z <= 8; z++) {
                world.set(x, FEET, z, FakeWorldView.WATER);
                world.set(x, FLOOR, z, FakeWorldView.WATER);
            }
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(7, FEET, 0), 0);

        assertTrue(path.complete(), "voda se má dát přeplavat");
        assertTrue(path.waypoints().stream()
                        .anyMatch(p -> world.traitsAt(p).liquid()),
                "cesta má vést vodou (plavání)");
    }

    @Test
    void seskociZVyskyDoHlubokeVody() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Útes: bot startuje na věži výšky 8; hned vedle bazén hloubky 2.
        int towerTop = FEET + 8;
        world.wall(0, FEET, towerTop - 1, 0);
        for (int x = 1; x <= 2; x++) {
            world.set(x, FEET, 0, FakeWorldView.WATER);
            world.set(x, FLOOR, 0, FakeWorldView.WATER);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, towerTop, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete(), "seskok do hluboké vody má být povolen");
        assertTrue(path.waypoints().stream().anyMatch(p -> world.traitsAt(p).liquid()),
                "cesta má vést dopadem do vody: " + path.waypoints());
    }

    @Test
    void neseskociZVyskyDoLavy() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Stejný útes, ale dole láva – seskok je zakázaný a cesta nevede.
        int towerTop = FEET + 8;
        world.wall(0, FEET, towerTop - 1, 0);
        for (int x = 1; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                world.set(x, FEET, z, FakeWorldView.HAZARD);
                world.set(x, FLOOR, z, FakeWorldView.HAZARD);
            }
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, towerTop, 0), new BlockPos(6, FEET, 0), 2000);

        assertFalse(path.complete(), "do lávy se nesmí seskočit ani vstoupit");
        for (BlockPos waypoint : path.waypoints()) {
            assertFalse(world.traitsAt(waypoint).hazard(),
                    "žádný waypoint nesmí být v lávě: " + waypoint);
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
