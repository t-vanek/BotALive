package dev.botalive.core.pathfinding;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Osobnost v cenách cest: dva boti ve stejném terénu volí různé trasy.
 */
class PathCostsTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    @Test
    void profilJeDeterministickyASmysluplny() {
        PathCosts brave = PathCosts.of(personality(1.0, 0.0, 0.0));
        PathCosts cautious = PathCosts.of(personality(0.0, 1.0, 0.0));
        PathCosts lazy = PathCosts.of(personality(0.5, 0.5, 1.0));

        assertTrue(brave.gapJump() < cautious.gapJump(), "odvaha zlevňuje skoky");
        assertTrue(cautious.hazardMargin() > brave.hazardMargin(), "opatrnost drží odstup od lávy");
        assertTrue(cautious.drop() >= 1.0, "seskoky nikdy pod základ (přípustnost yLevel heuristiky)");
        assertTrue(lazy.climb() > PathCosts.of(personality(0.5, 0.5, 0.0)).climb(),
                "lenost zdražuje šplhání");
        assertTrue(PathCosts.of(null).equals(PathCosts.DEFAULT), "bez osobnosti neutrální profil");
        assertEquals(PathCosts.of(personality(1.0, 0.0, 0.0)), brave, "profil je deterministický");
    }

    @Test
    void odvaznySkaceOpatrnyObchazi() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Rokle šířky 2 (x 3..4, z −8..2, hloubka 12) přes přímou trasu;
        // obchůzka vede severně přes z=3. Odvážnému se vyplatí sprint-skok,
        // opatrnému s dražší přirážkou obchůzka.
        for (int x = 3; x <= 4; x++) {
            for (int z = -8; z <= 2; z++) {
                for (int y = FLOOR - 12; y <= FLOOR; y++) {
                    world.set(x, y, z, FakeWorldView.AIRLIKE);
                }
            }
        }
        BlockPos start = new BlockPos(0, FEET, 0);
        BlockPos goal = new BlockPos(8, FEET, 0);

        Path bravePath = new AStarPathfinder(world, List.of(),
                PathCosts.of(personality(1.0, 0.0, 0.0)))
                .findPath(start, goal, 0);
        Path cautiousPath = new AStarPathfinder(world, List.of(),
                PathCosts.of(personality(0.0, 1.0, 0.0)))
                .findPath(start, goal, 0);

        assertTrue(bravePath.complete() && cautiousPath.complete(),
                "oba musí dorazit (jinou cestou)");
        assertTrue(hasGapJump(bravePath), "odvážný má rokli přeskočit: " + bravePath.waypoints());
        assertTrue(!hasGapJump(cautiousPath),
                "opatrný nemá skákat: " + cautiousPath.waypoints());
        assertTrue(cautiousPath.waypoints().stream().anyMatch(p -> p.z() >= 3),
                "opatrný má rokli obejít severem: " + cautiousPath.waypoints());
    }

    /** Obsahuje cesta skokový segment (sousední waypointy dál než 1 blok)? */
    private static boolean hasGapJump(Path path) {
        List<BlockPos> wps = path.waypoints();
        for (int i = 1; i < wps.size(); i++) {
            if (Math.abs(wps.get(i).x() - wps.get(i - 1).x()) > 1
                    || Math.abs(wps.get(i).z() - wps.get(i - 1).z()) > 1) {
                return true;
            }
        }
        return false;
    }

    private static Personality personality(double courage, double caution, double laziness) {
        Map<Trait, Double> traits = Map.of(
                Trait.COURAGE, courage, Trait.CAUTION, caution, Trait.LAZINESS, laziness);
        return new Personality() {
            @Override
            public double trait(Trait trait) {
                return traits.getOrDefault(trait, 0.5);
            }

            @Override
            public Map<Trait, Double> traits() {
                return traits;
            }

            @Override
            public long seed() {
                return 1;
            }

            @Override
            public String archetype() {
                return "test";
            }
        };
    }
}
