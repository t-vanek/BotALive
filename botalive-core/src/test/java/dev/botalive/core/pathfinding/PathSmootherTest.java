package dev.botalive.core.pathfinding;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testy vyhlazení trasy (string pulling).
 *
 * <p>Zkratka smí vést jen po souvislé pochozí ploše – roh za zdí, změna
 * výšky nebo pavučina v koridoru vyhlazení zarazí a bot jde lomeně po
 * waypointech (tam je lomená čára správně).</p>
 */
class PathSmootherTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    private static BlockPos wp(int x, int z) {
        return new BlockPos(x, FEET, z);
    }

    @Test
    void rovnyKoridorVidiAzNaKonecOkna() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        List<BlockPos> waypoints = List.of(
                wp(1, 0), wp(2, 0), wp(3, 0), wp(4, 0), wp(5, 0), wp(6, 0), wp(7, 0), wp(8, 0));

        int smooth = PathSmoother.smoothTarget(world, new Vec3(0.5, FEET, 0.5), waypoints, 0);

        assertEquals(PathSmoother.LOOKAHEAD, smooth,
                "na volné rovince se míří na konec okna vyhlazení");
    }

    @Test
    void rohZaZdiSeNerezue() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Zeď v rohu „L" trasy – přímá zkratka by jí prošla.
        world.wall(1, FEET, FEET + 1, 1);
        world.wall(1, FEET, FEET + 1, 2);
        List<BlockPos> waypoints = List.of(wp(2, 0), wp(2, 1), wp(2, 2));

        int smooth = PathSmoother.smoothTarget(world, new Vec3(0.5, FEET, 0.5), waypoints, 0);

        assertEquals(0, smooth, "zkratka nesmí škrtnout o zeď v rohu");
    }

    @Test
    void zmenaVyskyVyhlazeniZastavi() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(3, FEET, 0, FakeWorldView.SOLID); // schod, na který se skáče
        List<BlockPos> waypoints = List.of(
                wp(1, 0), wp(2, 0), new BlockPos(3, FEET + 1, 0), new BlockPos(4, FEET + 1, 0));

        int smooth = PathSmoother.smoothTarget(world, new Vec3(0.5, FEET, 0.5), waypoints, 0);

        assertEquals(1, smooth, "před skokem na blok se vyhlazení zastaví");
    }

    @Test
    void pavucinaVKoridoruBlokujeZkratku() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Pavučina ve vnitřním rohu „L" trasy – diagonální zkratka by ji škrtla.
        world.set(1, FEET, 1, FakeWorldView.WEB);
        List<BlockPos> waypoints = List.of(wp(1, 0), wp(2, 0), wp(2, 1), wp(2, 2));

        int smooth = PathSmoother.smoothTarget(world, new Vec3(0.5, FEET, 0.5), waypoints, 0);

        assertEquals(1, smooth, "zkratka rohem s pavučinou se nepouští");
    }

    @Test
    void skokovySegmentSeNevyhlazuje() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Waypointy se skokem přes mezeru (vzdálenost 2) – vyžadují přesný rozběh.
        List<BlockPos> waypoints = List.of(wp(1, 0), wp(3, 0), wp(4, 0));

        int smooth = PathSmoother.smoothTarget(world, new Vec3(0.5, FEET, 0.5), waypoints, 0);

        assertEquals(0, smooth, "skokový segment zůstává na přesných waypointech");
    }

    @Test
    void srazVedleTrasyZkratkuNepusti() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Propast v rohu diagonální zkratky (chybí podlaha).
        world.set(1, FLOOR, 1, FakeWorldView.AIRLIKE);
        world.set(2, FLOOR, 1, FakeWorldView.AIRLIKE);
        for (int y = FLOOR - 1; y >= FLOOR - 10; y--) {
            world.set(1, y, 1, FakeWorldView.AIRLIKE);
            world.set(2, y, 1, FakeWorldView.AIRLIKE);
        }
        List<BlockPos> waypoints = List.of(wp(1, 0), wp(2, 0), wp(3, 0), wp(3, 1), wp(3, 2));

        int smooth = PathSmoother.smoothTarget(world, new Vec3(0.5, FEET, 0.5), waypoints, 0);

        // Zkratka na (3,1)/(3,2) by vedla rohem hitboxu nad propastí.
        assertEquals(2, smooth, "nad díru v podlaze se zkratka nepouští");
    }
}
