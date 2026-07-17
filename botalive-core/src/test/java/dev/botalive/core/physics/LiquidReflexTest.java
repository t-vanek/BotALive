package dev.botalive.core.physics;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy tekutinových reflexů (vynoření z vody, útěk z lávy).
 */
class LiquidReflexTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    @Test
    void ponorenyStojiciBotVyplave() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Hluboká voda: nohy i hlava pod hladinou.
        for (int y = FEET; y <= FEET + 3; y++) {
            world.set(0, y, 0, FakeWorldView.WATER);
        }
        MoveInput out = LiquidReflex.apply(MoveInput.IDLE, false,
                new Vec3(0.5, FEET, 0.5), world);
        assertTrue(out.jump(), "ponořený bot musí držet skok (vyplavat)");
    }

    @Test
    void aktivniPlaveckouNavigaciNechavaByt() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int y = FEET; y <= FEET + 3; y++) {
            world.set(0, y, 0, FakeWorldView.WATER);
        }
        // Navigace se zanořuje záměrně (jump=false, směr nenulový) – reflex nesahá.
        MoveInput diving = MoveInput.of(new Vec3(1, 0, 0), false, false);
        MoveInput out = LiquidReflex.apply(diving, true,
                new Vec3(0.5, FEET, 0.5), world);
        assertFalse(out.jump(), "záměrné potápění navigace se nesmí přebít");
    }

    @Test
    void vLaveUtikaKNejblizsimuBezpeci() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Lávové jezírko protažené na západ i do stran – jednoznačně nejbližší
        // bezpečný břeh je na východě (x=3).
        for (int x = -4; x <= 2; x++) {
            for (int z = -4; z <= 4; z++) {
                world.set(x, FEET, z, FakeWorldView.HAZARD);
            }
        }
        MoveInput out = LiquidReflex.apply(MoveInput.IDLE, true,
                new Vec3(0.5, FEET, 0.5), world);
        assertTrue(out.jump(), "v lávě se drží skok (stoupání)");
        assertTrue(out.direction().x() > 0.5,
                "útěk má mířit k nejbližšímu bezpečí na východě: " + out.direction());
    }

    @Test
    void naSuchuNechavaVstupBezeZmeny() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        MoveInput walking = MoveInput.of(new Vec3(0, 0, 1), true, false);
        MoveInput out = LiquidReflex.apply(walking, true,
                new Vec3(0.5, FEET, 0.5), world);
        assertEquals(walking, out, "na suchu reflex do vstupu nezasahuje");
    }
}
