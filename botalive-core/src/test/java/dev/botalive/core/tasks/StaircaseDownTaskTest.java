package dev.botalive.core.tasks;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ověřuje bezpečnostní bránu sestupu schodištěm: sestupuje jen skrz pevný,
 * bezpečný materiál, nikdy nad prázdno, do tekutiny ani k lávě.
 */
class StaircaseDownTaskTest {

    private static final BlockPos FEET = new BlockPos(0, 65, 0);

    @Test
    void allowsDescentThroughSolidSafeSlope() {
        // Podlaha stupně (feet + (1,-2,0) = 1,63,0) pevná, hlava i stupeň volné.
        FakeWorldView world = new FakeWorldView(63);
        assertTrue(StaircaseDownTask.canDescendStep(world, FEET, 1, 0));
    }

    @Test
    void refusesOpenDrop() {
        // Pod stupněm prázdno (1,63,0 je vzduch) – není na co dosednout.
        FakeWorldView world = new FakeWorldView(62);
        assertFalse(StaircaseDownTask.canDescendStep(world, FEET, 1, 0));
    }

    @Test
    void refusesFloorHazard() {
        FakeWorldView world = new FakeWorldView(63);
        world.set(1, 63, 0, FakeWorldView.HAZARD); // láva jako podlaha stupně
        assertFalse(StaircaseDownTask.canDescendStep(world, FEET, 1, 0));
    }

    @Test
    void refusesLiquidBehindStep() {
        FakeWorldView world = new FakeWorldView(63);
        world.set(2, 64, 0, FakeWorldView.WATER); // za stěnou stupně voda
        assertFalse(StaircaseDownTask.canDescendStep(world, FEET, 1, 0));
    }

    @Test
    void refusesLiquidAtHead() {
        FakeWorldView world = new FakeWorldView(63);
        world.set(1, 65, 0, FakeWorldView.WATER); // tekutina v prostoru hlavy
        assertFalse(StaircaseDownTask.canDescendStep(world, FEET, 1, 0));
    }
}
