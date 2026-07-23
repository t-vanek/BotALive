package dev.botalive.core.build.plan;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zarovnání prstence kolem domu (apron): srovná úroveň podlahy okolo půdorysu,
 * aby dům netrčel do svahu. Jen výkopy; volitelné a hazard-safe.
 */
class BuildPlannerApronTest {

    /** Půdorys 1×1: dům na (0,64,0), podlahová opora (0,63,0). */
    private static BuildPlan plan() {
        BlockPos house = new BlockPos(0, 64, 0);
        return new BuildPlan(
                List.of(new PlacementCell(house, BlockSpec.GENERIC)),
                List.of(house),          // clearVolume
                List.of(house.down()),   // groundColumns (0,63,0) → podlaha na y=64
                List.of(), house, Optional.empty(), false);
    }

    @Test
    void gradesRaisedRingToFloorLevel() {
        FakeWorldView world = new FakeWorldView(63);
        BlockPos bump = new BlockPos(1, 64, 0); // trčí do prstence na úrovni podlahy
        world.set(bump.x(), bump.y(), bump.z(), FakeWorldView.SOLID);

        List<BlockPos> mine = BuildPlanner.schedule(plan(), world, true).mine();
        assertTrue(mine.contains(bump), "trčící blok v prstenci se vytěží");
    }

    @Test
    void leavesRingAloneWhenDisabled() {
        FakeWorldView world = new FakeWorldView(63);
        BlockPos bump = new BlockPos(1, 64, 0);
        world.set(bump.x(), bump.y(), bump.z(), FakeWorldView.SOLID);

        List<BlockPos> mine = BuildPlanner.schedule(plan(), world).mine();
        assertFalse(mine.contains(bump), "bez zarovnání se prstenec neřeší (parita)");
    }

    @Test
    void flatTerrainNeedsNoGrading() {
        FakeWorldView world = new FakeWorldView(63); // vše nad podlahou je vzduch
        assertTrue(BuildPlanner.schedule(plan(), world, true).mine().isEmpty(),
                "na rovině není co srovnávat");
    }

    @Test
    void neverDigsHazard() {
        FakeWorldView world = new FakeWorldView(63);
        BlockPos lava = new BlockPos(1, 64, 0);
        world.set(lava.x(), lava.y(), lava.z(), FakeWorldView.HAZARD);

        assertFalse(BuildPlanner.schedule(plan(), world, true).mine().contains(lava),
                "hazard (láva/magma) se v prstenci nebourá");
    }
}
