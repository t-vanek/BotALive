package dev.botalive.core.vehicle;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy klientské simulace stridera na lávě.
 */
class StriderPhysicsTest {

    private static final int LAVA_Y = 31;
    private static final double FEET_Y = LAVA_Y + 1;

    /** Lávový oceán: láva v pruhu x −4..N, dál pevný břeh (podlaha světa). */
    private static FakeWorldView lavaOcean(int lavaUntilX) {
        FakeWorldView world = new FakeWorldView(LAVA_Y);
        for (int x = -4; x <= lavaUntilX; x++) {
            for (int z = -8; z <= 8; z++) {
                world.set(x, LAVA_Y, z, FakeWorldView.HAZARD);
            }
        }
        return world;
    }

    @Test
    void zrychliNaJizdniRychlostNaLave() {
        StriderPhysics strider = new StriderPhysics(lavaOcean(500),
                new Vec3(0.5, FEET_Y, 0.5), -90);
        Vec3 target = new Vec3(400, FEET_Y, 0.5);

        for (int i = 0; i < 60; i++) {
            strider.stepToward(target);
        }
        // Jízdní rychlost osedlaného stridera na lávě ~0.096 bloku/tick.
        assertEquals(0.096, strider.speed(), 0.005, "jízdní rychlost na lávě");
        assertTrue(strider.position().x() > 4, "strider má urazit vzdálenost");
        assertFalse(strider.ashore(), "uprostřed oceánu žádný břeh");
    }

    @Test
    void otaceniJeOmezeneUhlovouRychlosti() {
        StriderPhysics strider = new StriderPhysics(lavaOcean(200),
                new Vec3(0.5, FEET_Y, 0.5), 0);
        Vec3 target = new Vec3(150, FEET_Y, 0.5); // cíl na východ (yaw −90)

        strider.stepToward(target);
        assertTrue(Math.abs(strider.yaw()) <= 5 + 0.001, "otáčení max 5°/tick");

        for (int i = 0; i < 60; i++) {
            strider.stepToward(target);
        }
        assertEquals(-90, strider.yaw(), 6, "strider se má srovnat k cíli");
    }

    @Test
    void vystoupaniNaBrehJizduKonci() {
        StriderPhysics strider = new StriderPhysics(lavaOcean(20),
                new Vec3(0.5, FEET_Y, 0.5), -90);
        Vec3 target = new Vec3(60, FEET_Y, 0.5); // cíl za břehem

        for (int i = 0; i < 600 && !strider.ashore(); i++) {
            strider.stepToward(target);
        }
        assertTrue(strider.ashore(), "strider má vystoupat na pevninu");
        assertEquals(0, strider.speed(), 1e-9);
        assertTrue(strider.position().x() > 20,
                "břeh začíná za lávou, x=" + strider.position().x());
    }

    @Test
    void serieKorekciBezCistehoTickuZnamenaStuck() {
        StriderPhysics strider = new StriderPhysics(lavaOcean(100),
                new Vec3(0.5, FEET_Y, 0.5), -90);
        Vec3 serverPos = new Vec3(0.5, FEET_Y, 0.5);
        for (int i = 0; i < 3; i++) {
            strider.correct(serverPos, -90);
        }
        assertTrue(strider.stuck(), "3 korekce v řadě = server jízdu nepouští");
        assertEquals(0, strider.speed(), 1e-9);
    }

    @Test
    void ojedinelaKorekceZaJizdyStuckNeznamena() {
        StriderPhysics strider = new StriderPhysics(lavaOcean(300),
                new Vec3(0.5, FEET_Y, 0.5), -90);
        Vec3 target = new Vec3(250, FEET_Y, 0.5);
        for (int i = 0; i < 30; i++) {
            strider.stepToward(target);
            if (i % 10 == 0) {
                strider.correct(strider.position(), strider.yaw());
            }
        }
        assertFalse(strider.stuck(), "korekce prokládané čistou jízdou nevadí");
        assertTrue(strider.speed() > 0.05, "jízda pokračuje");
    }
}
