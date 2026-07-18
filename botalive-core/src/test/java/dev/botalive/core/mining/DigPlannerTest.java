package dev.botalive.core.mining;

import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy plánovače výkopu – bezpečnostní pravidla a dosažení cíle.
 */
class DigPlannerTest {

    @Test
    void vodorovnaStolaDojdeKCili() {
        BlockPos feet = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(5, 64, 0);
        List<DigPlanner.Step> steps = DigPlanner.plan(feet, target, 32);
        assertEquals(5, steps.size());
        assertEquals(target, steps.getLast().feet());
        // Štola 1×2: každý krok čistí nohy a hlavu.
        for (DigPlanner.Step step : steps) {
            assertEquals(2, step.toBreak().size());
        }
    }

    @Test
    void nikdyNekopeKolmoDolu() {
        BlockPos feet = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(0, 58, 0); // přímo pod botem
        List<DigPlanner.Step> steps = DigPlanner.plan(feet, target, 64);
        BlockPos current = feet;
        for (DigPlanner.Step step : steps) {
            // Žádný rozbíjený blok není přímo pod aktuální pozicí nohou.
            BlockPos below = current.down();
            assertFalse(step.toBreak().contains(below),
                    "krok kope kolmo dolů pod nohama: " + step);
            // Sestup vždy kombinuje pokles s vodorovným posunem (schodiště).
            if (step.feet().y() < current.y()) {
                assertTrue(step.feet().x() != current.x() || step.feet().z() != current.z(),
                        "sestup bez vodorovného kroku: " + step);
            }
            current = step.feet();
        }
        assertEquals(target.y(), current.y(), "schodiště má dosáhnout cílové hloubky");
    }

    @Test
    void sestupCistiTriBlokyKvuliPruchodnosti() {
        List<DigPlanner.Step> steps = DigPlanner.plan(
                new BlockPos(0, 64, 0), new BlockPos(3, 61, 0), 32);
        for (DigPlanner.Step step : steps) {
            if (!step.toBreak().isEmpty() && step.toBreak().size() != 2) {
                assertEquals(3, step.toBreak().size(),
                        "sestupný krok musí čistit 3 bloky (průchodnost přes hranu)");
            }
        }
    }

    @Test
    void cilNadHlavouSeNeplanuje() {
        assertTrue(DigPlanner.plan(new BlockPos(0, 64, 0), new BlockPos(0, 70, 0), 32).isEmpty(),
                "nahoru se nekope (žádné pilířování)");
    }

    @Test
    void schodisteDosahneHloubky() {
        List<DigPlanner.Step> steps = DigPlanner.staircase(new BlockPos(0, 64, 0), 52, 0, 64);
        assertFalse(steps.isEmpty());
        assertEquals(52, steps.getLast().feet().y());
        // Každý krok klesá o 1 a posouvá se vodorovně o 1 (nikdy kolmo dolů).
        BlockPos current = new BlockPos(0, 64, 0);
        for (DigPlanner.Step step : steps) {
            assertEquals(current.y() - 1, step.feet().y());
            assertEquals(1, Math.abs(step.feet().x() - current.x())
                    + Math.abs(step.feet().z() - current.z()));
            current = step.feet();
        }
    }

    @Test
    void budgetOmezujeDelkuPlanu() {
        List<DigPlanner.Step> steps = DigPlanner.plan(
                new BlockPos(0, 64, 0), new BlockPos(100, 64, 0), 10);
        assertEquals(10, steps.size(), "plán respektuje strop kroků");
    }

    // ---------------------------------------------------------- sonda podlahy

    @Test
    void podlahaHnedPodNohamaJeOk() {
        var world = new dev.botalive.core.testutil.FakeWorldView(63);
        assertTrue(DigPlanner.hasFloorBelow(world, new dev.botalive.core.util.BlockPos(0, 64, 0), 3));
    }

    @Test
    void stropKavernySondaOdmitne() {
        var world = new dev.botalive.core.testutil.FakeWorldView(63);
        // Kaverna: pod chodidly 10 bloků vzduchu.
        for (int y = 63; y >= 54; y--) {
            world.set(0, y, 0, dev.botalive.core.world.BlockTraits.AIR);
        }
        assertFalse(DigPlanner.hasFloorBelow(world, new dev.botalive.core.util.BlockPos(0, 64, 0), 3),
                "prokopnutí do kaverny musí sonda odmítnout");
    }

    @Test
    void jezeroPodStropemSondaOdmitne() {
        var world = new dev.botalive.core.testutil.FakeWorldView(63);
        world.set(0, 63, 0, dev.botalive.core.testutil.FakeWorldView.WATER);
        assertFalse(DigPlanner.hasFloorBelow(world, new dev.botalive.core.util.BlockPos(0, 64, 0), 3),
                "tekutina pod chodidly není podlaha");
    }
}
