package dev.botalive.core.build.plan;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Srovnání REZERVOVANÉ platformy pro pozdější růst ({@link BuildPlanner#schedule}
 * s {@code reserveGround}): hrboly trčící do svahu se v celém rezervovaném
 * půdorysu srovnají (výkop), ale díry se NEzasypávají – rezerva je dig-only, aby
 * nikdy nespotřebovala bloky určené domu (stejná DNA jako apron).
 */
class ReserveGradingTest {

    private static final int FLOOR_Y = 63;
    private static final BlockPos ORIGIN = new BlockPos(0, FLOOR_Y + 1, 0);

    @Test
    void reserveDigsBumpsButNeverFills() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        // Hrbol v rezervovaném okraji na úrovni podlahy; díra pod platformou jinde.
        BlockPos bump = new BlockPos(10, ORIGIN.y(), 12);
        world.set(bump.x(), bump.y(), bump.z(), FakeWorldView.SOLID);
        BlockPos holeGround = new BlockPos(11, ORIGIN.y() - 1, 12);
        world.set(holeGround.x(), holeGround.y(), holeGround.z(),
                dev.botalive.core.world.BlockTraits.AIR);

        HouseGenerator gen = new HouseGenerator(5, 3);
        BuildPlan plan = BuildPlan.of(gen, ORIGIN, Cardinal.NORTH);
        List<BlockPos> reserve = List.of(
                new BlockPos(10, ORIGIN.y() - 1, 12), // sloupec s hrbolem nad ním
                holeGround);                          // sloupec s dírou

        BuildSchedule schedule = BuildPlanner.schedule(plan, world, false, reserve);

        assertTrue(schedule.mine().contains(bump),
                "hrbol v rezervě se srovná (výkop)");
        assertFalse(schedule.fill().contains(holeGround),
                "díru v rezervě rezerva nezasypává (dig-only, nespotřebuje bloky domu)");
    }

    @Test
    void noReserveKeepsScheduleUnchanged() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        HouseGenerator gen = new HouseGenerator(5, 3);
        BuildPlan plan = BuildPlan.of(gen, ORIGIN, Cardinal.NORTH);
        // Bez rezervy (prázdný seznam) = dnešní chování; na rovině žádné výkopy.
        BuildSchedule schedule = BuildPlanner.schedule(plan, world, false, List.of());
        assertTrue(schedule.mine().isEmpty(), "na rovině bez rezervy se nic nekope");
    }
}
