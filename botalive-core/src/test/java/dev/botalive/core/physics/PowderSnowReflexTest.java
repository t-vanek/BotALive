package dev.botalive.core.physics;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy reflexu úniku z prašanu.
 */
class PowderSnowReflexTest {

    private static final int FLOOR = 63;
    private static final double FEET_Y = FLOOR + 1;

    @Test
    void vPrasanuDrziSkokASmerKBezpeci() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(0, FLOOR + 1, 0, FakeWorldView.POWDER); // bot vězí v prašanu

        MoveInput out = PowderSnowReflex.apply(MoveInput.IDLE, true,
                new Vec3(0.5, FEET_Y, 0.5), world);

        assertTrue(out.jump(), "v prašanu se drží skok (stoupání ven)");
        assertTrue(out.direction().horizontalLength() > 0.9,
                "reflex má mířit k bezpečnému bloku, dir=" + out.direction());
        assertFalse(out.sprint(), "v prašanu se nesprintuje");
    }

    @Test
    void mimoPrasanBezeZmeny() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        MoveInput in = MoveInput.walk(new Vec3(1, 0, 0));

        MoveInput out = PowderSnowReflex.apply(in, false,
                new Vec3(0.5, FEET_Y, 0.5), world);

        assertSame(in, out, "mimo prašan reflex nezasahuje");
    }
}
