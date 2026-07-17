package dev.botalive.core.physics;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy fyziky bota (pád, kolize se zdí, skok přes blok).
 */
class BotPhysicsTest {

    private static final int FLOOR = 63;
    private static final double FEET_Y = FLOOR + 1;

    @Test
    void padDopadneNaPodlahu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y + 6, 0.5));

        for (int i = 0; i < 60 && !physics.onGround(); i++) {
            physics.step(MoveInput.IDLE);
        }

        assertTrue(physics.onGround(), "bot má dopadnout na zem");
        assertEquals(FEET_Y, physics.position().y(), 0.01, "nohy mají stát na podlaze");
    }

    @Test
    void chuzeDoZdiZastaviBota() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Zeď výšky 2 na x=3 (skrz ni to nejde ani skokem).
        for (int z = -2; z <= 2; z++) {
            world.wall(3, (int) FEET_Y, (int) FEET_Y + 1, z);
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        MoveInput east = MoveInput.walk(new Vec3(1, 0, 0));
        boolean collided = false;
        for (int i = 0; i < 80; i++) {
            physics.step(east);
            collided |= physics.horizontalCollision();
        }

        assertTrue(collided, "bot má narazit do zdi");
        assertTrue(physics.position().x() < 3.0, "bot nesmí projít zdí, x=" + physics.position().x());
    }

    @Test
    void skokemPrekonaJedenBlok() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Schod výšky 1 na x=3.
        for (int z = -2; z <= 2; z++) {
            world.set(3, (int) FEET_Y, z, FakeWorldView.SOLID);
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        for (int i = 0; i < 120 && physics.position().x() < 4.0; i++) {
            physics.step(MoveInput.of(new Vec3(1, 0, 0), false, physics.onGround()));
        }

        assertTrue(physics.position().x() >= 4.0,
                "bot má skokem vylézt na blok, x=" + physics.position().x());
        assertEquals(FEET_Y + 1, physics.position().y(), 0.05, "bot má stát na schodu");
    }

    @Test
    void plavecSeSkokemVyhoupneNaBreh() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Bazén (x 0..2) o blok zapuštěný; břeh od x=3 v úrovni FEET_Y.
        for (int x = 0; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, (int) FEET_Y - 1, z, FakeWorldView.WATER);
            }
        }
        // Podlahu pod bazénem posunout o 2 dolů (voda hloubky 2).
        for (int x = 0; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, (int) FEET_Y - 2, z, FakeWorldView.WATER);
            }
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(1.5, FEET_Y - 1, 0.5));

        // Plavat na východ se skokem – u stěny břehu se má vyhoupnout nahoru.
        MoveInput swim = new MoveInput(new Vec3(1, 0, 0), false, true, false);
        for (int i = 0; i < 200 && physics.position().x() < 3.6; i++) {
            physics.step(swim);
        }

        assertTrue(physics.position().x() >= 3.6,
                "plavec se má dostat na břeh, x=" + physics.position().x());
        assertTrue(physics.position().y() >= FEET_Y - 0.05,
                "plavec má stát na úrovni břehu, y=" + physics.position().y());
    }

    @Test
    void knockbackNastaviRychlost() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));
        physics.step(MoveInput.IDLE); // usadit na zem

        physics.setVelocity(new Vec3(0.5, 0.4, 0));
        physics.step(MoveInput.IDLE);

        assertTrue(physics.position().x() > 0.5, "knockback má bota posunout");
        assertTrue(physics.position().y() > FEET_Y, "knockback má bota nadzvednout");
    }
}
