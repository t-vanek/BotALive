package dev.botalive.core.vehicle;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy klientské simulace lodi.
 */
class BoatPhysicsTest {

    private static final int WATER_Y = 62;

    /** Jezero: voda na hladině WATER_Y v pruhu x 0..N, dál pevnina. */
    private static FakeWorldView lake(int waterUntilX) {
        FakeWorldView world = new FakeWorldView(WATER_Y); // podlaha = dno
        for (int x = -4; x <= waterUntilX; x++) {
            for (int z = -8; z <= 8; z++) {
                world.set(x, WATER_Y, z, FakeWorldView.WATER);
            }
        }
        return world;
    }

    @Test
    void zrychliNaVanillaTerminalniRychlost() {
        BoatPhysics boat = new BoatPhysics(lake(500), new Vec3(0.5, WATER_Y + 0.9, 0.5), -90);
        Vec3 target = new Vec3(400, WATER_Y + 0.9, 0.5);

        for (int i = 0; i < 100; i++) {
            boat.stepToward(target);
        }
        // Terminální rychlost: accel 0.04, drag 0.9 → 0.36 bloků/tick (~7.2 m/s).
        assertEquals(0.36, boat.speed(), 0.02, "terminální rychlost lodi");
        assertTrue(boat.position().x() > 20, "loď má urazit vzdálenost");
    }

    @Test
    void otociSeKCiliSOmezenouRychlosti() {
        BoatPhysics boat = new BoatPhysics(lake(200), new Vec3(0.5, WATER_Y + 0.9, 0.5), 0);
        Vec3 target = new Vec3(150, WATER_Y + 0.9, 0.5); // cíl na východ (yaw -90)

        boat.stepToward(target);
        assertTrue(Math.abs(boat.yaw()) <= 2.5 + 0.001, "otáčení max 2.5°/tick");

        for (int i = 0; i < 60; i++) {
            boat.stepToward(target);
        }
        assertEquals(-90, boat.yaw(), 5, "loď se má srovnat k cíli");
    }

    @Test
    void najetiNaBrehZastavi() {
        BoatPhysics boat = new BoatPhysics(lake(20), new Vec3(0.5, WATER_Y + 0.9, 0.5), -90);
        Vec3 target = new Vec3(60, WATER_Y + 0.9, 0.5); // cíl za břehem

        for (int i = 0; i < 200 && !boat.aground(); i++) {
            boat.stepToward(target);
        }
        assertTrue(boat.aground(), "loď má detekovat mělčinu");
        assertEquals(0, boat.speed(), 1e-9);
        assertTrue(boat.position().x() <= 21.5, "loď nesmí vyjet na souš");
    }

    @Test
    void vidiVoduPredPridi() {
        BoatPhysics boat = new BoatPhysics(lake(20), new Vec3(0.5, WATER_Y + 0.9, 0.5), -90);
        assertTrue(boat.waterAhead(5), "před přídí je voda");

        // Doplout ke břehu a zkontrolovat detekci souše před přídí.
        Vec3 target = new Vec3(19, WATER_Y + 0.9, 0.5);
        for (int i = 0; i < 120; i++) {
            boat.stepToward(target);
        }
        assertFalse(boat.waterAhead(6), "před břehem už voda není");
    }

    @Test
    void serieKorekciZnamenaTvrdyAground() {
        BoatPhysics boat = new BoatPhysics(lake(100), new Vec3(0.5, WATER_Y + 0.9, 0.5), 0);
        // Server loď opakovaně vrací – klient vodu vidí, server ne (břeh).
        for (int i = 0; i < 3; i++) {
            boat.correct(new Vec3(0.5, WATER_Y + 0.9, 0.5), 0);
        }
        assertTrue(boat.aground(), "série korekcí = tvrdý aground (nepřetlačovat se)");
        assertEquals(0, boat.speed(), 1e-9);
    }

    @Test
    void ojedinelaKorekceAgroundNespousti() {
        BoatPhysics boat = new BoatPhysics(lake(100), new Vec3(0.5, WATER_Y + 0.9, 0.5), 0);
        Vec3 target = new Vec3(50, WATER_Y + 0.9, 0.5);
        boat.correct(new Vec3(0.5, WATER_Y + 0.9, 0.5), 0);
        for (int i = 0; i < 5; i++) {
            boat.stepToward(target); // čisté ticky sérii rozpouští
        }
        boat.correct(boat.position(), 0);
        assertFalse(boat.aground(), "běžná korekce za jízdy aground nespouští");
    }

    @Test
    void korekceOdServeruPrepisuje() {
        BoatPhysics boat = new BoatPhysics(lake(100), new Vec3(0.5, WATER_Y + 0.9, 0.5), 0);
        boat.correct(new Vec3(10, WATER_Y + 0.9, 10), 45);

        assertEquals(10, boat.position().x(), 1e-9);
        assertEquals(45, boat.yaw(), 1e-9);
    }
}
