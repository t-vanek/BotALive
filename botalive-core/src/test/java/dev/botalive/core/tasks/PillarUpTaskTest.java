package dev.botalive.core.tasks;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy bezpečnostní kontroly sloupce pro pilířování.
 *
 * <p>Pilíř roste do otevřeného prostoru – strop, tekutina, hazard nebo
 * nenačtený chunk ve sloupci musí výstup zarazit ještě před prvním skokem
 * (v půlce pilíře by byl bot v pasti).</p>
 */
class PillarUpTaskTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    @Test
    void volnySloupecPovoliVystup() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        assertTrue(PillarUpTask.columnClear(world, new BlockPos(0, FEET, 0), FEET + 8),
                "otevřené nebe nad botem má výstup povolit");
    }

    @Test
    void stropVystupZarazi() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(0, FEET + 5, 0, FakeWorldView.SOLID);
        assertFalse(PillarUpTask.columnClear(world, new BlockPos(0, FEET, 0), FEET + 8),
                "strop ve sloupci musí výstup zakázat");
    }

    @Test
    void plotVeSloupciVystupZarazi() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(0, FEET + 3, 0, FakeWorldView.FENCE);
        assertFalse(PillarUpTask.columnClear(world, new BlockPos(0, FEET, 0), FEET + 8),
                "i částečná kolize (plot) ve sloupci vadí");
    }

    @Test
    void vodaVeSloupciVystupZarazi() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(0, FEET + 2, 0, FakeWorldView.WATER);
        assertFalse(PillarUpTask.columnClear(world, new BlockPos(0, FEET, 0), FEET + 8),
                "tekutina ve sloupci musí výstup zakázat (pilíř by ji zalil)");
    }

    @Test
    void hazardVeSloupciVystupZarazi() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(0, FEET + 4, 0, FakeWorldView.HAZARD);
        assertFalse(PillarUpTask.columnClear(world, new BlockPos(0, FEET, 0), FEET + 8),
                "hazard (láva) ve sloupci musí výstup zakázat");
    }

    @Test
    void cilVeStejneVysceNedavaSmysl() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        assertFalse(PillarUpTask.columnClear(world, new BlockPos(0, FEET, 0), FEET),
                "pilíř do vlastní výšky nedává smysl");
    }
}
