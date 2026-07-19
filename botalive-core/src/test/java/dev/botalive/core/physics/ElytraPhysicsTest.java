package dev.botalive.core.physics;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy pohybových efektů a elytrového letu: levitace zvedá, slow falling
 * brzdí pád, klouzání nese dopředu a přistání let ukončí bez zranění.
 */
class ElytraPhysicsTest {

    private static final int FLOOR = 63;
    private static final double FEET_Y = FLOOR + 1;

    @Test
    void levitaceZvedaBotaVzhuru() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));
        physics.effects(true, false);

        double startY = physics.position().y();
        for (int i = 0; i < 40; i++) {
            physics.step(MoveInput.IDLE);
        }
        assertTrue(physics.position().y() > startY + 1.0,
                "levitace má bota zvednout, y=" + physics.position().y());
        assertEquals(0, physics.fallDistance(), 1e-9, "levitace pád nekumuluje");
    }

    @Test
    void slowFallingPadaPomalejiNezGravitace() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BotPhysics slow = new BotPhysics(world, new Vec3(0.5, FEET_Y + 30, 0.5));
        slow.effects(false, true);
        BotPhysics normal = new BotPhysics(world, new Vec3(0.5, FEET_Y + 30, 0.5));

        for (int i = 0; i < 20; i++) {
            slow.step(MoveInput.IDLE);
            normal.step(MoveInput.IDLE);
        }
        assertTrue(slow.position().y() > normal.position().y() + 5,
                "slow falling má klesat výrazně pomaleji");
    }

    @Test
    void klouzaniNeseDopreduAZtraciVyskuPomalu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y + 90, 0.5));
        // Mírně skloněný pohled vpřed (typické klouzání).
        Vec3 look = unit(new Vec3(1, -0.15, 0));
        // Chvíli padat, pak rozevřít křídla (jako po seskoku z hrany).
        physics.step(MoveInput.IDLE);
        physics.step(MoveInput.IDLE);
        physics.startGliding(look);

        // Rozjezd: ze stoje se rychlost teprve staví.
        for (int i = 0; i < 40; i++) {
            physics.glideLook(look);
            physics.step(MoveInput.IDLE);
        }
        double cruiseStartX = physics.position().x();
        double cruiseStartY = physics.position().y();
        for (int i = 0; i < 60; i++) {
            physics.glideLook(look);
            physics.step(MoveInput.IDLE);
        }
        double dx = physics.position().x() - cruiseStartX;
        double dy = cruiseStartY - physics.position().y();
        assertTrue(physics.gliding(), "let nad zemí pokračuje");
        assertTrue(dx > 12, "klouzání má nést dopředu, dx=" + dx);
        assertTrue(dy < dx, "klouzavost má být lepší než 1:1 (dy=" + dy + ", dx=" + dx + ")");
    }

    @Test
    void pristaniUkonciLetBezPadovehoZraneni() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y + 40, 0.5));
        Vec3 look = unit(new Vec3(1, -0.3, 0));
        physics.step(MoveInput.IDLE);
        physics.step(MoveInput.IDLE);
        physics.startGliding(look);

        for (int i = 0; i < 400 && physics.gliding(); i++) {
            physics.glideLook(look);
            physics.step(MoveInput.IDLE);
        }
        assertFalse(physics.gliding(), "dotyk země má let ukončit");
        assertTrue(physics.onGround(), "bot má stát na zemi");
        assertEquals(0, physics.lastFallDamage(),
                "klouzavé přistání nemá zranit");
    }

    @Test
    void flarePohledVzhuruBrzdiKlesani() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y + 60, 0.5));
        Vec3 down = unit(new Vec3(1, -0.35, 0));
        physics.step(MoveInput.IDLE);
        physics.step(MoveInput.IDLE);
        physics.startGliding(down);
        // Nabrat rychlost klesáním...
        for (int i = 0; i < 30; i++) {
            physics.glideLook(down);
            physics.step(MoveInput.IDLE);
        }
        double sinkBefore = physics.velocity().y();
        // ...a podrovnat (pohled vzhůru).
        Vec3 up = unit(new Vec3(1, 0.3, 0));
        for (int i = 0; i < 10; i++) {
            physics.glideLook(up);
            physics.step(MoveInput.IDLE);
        }
        assertTrue(physics.velocity().y() > sinkBefore,
                "flare má klesání zbrzdit (před " + sinkBefore
                        + ", po " + physics.velocity().y() + ")");
    }

    private static Vec3 unit(Vec3 v) {
        double length = Math.sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z());
        return v.mul(1.0 / length);
    }
}
