package dev.botalive.core.vehicle;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.vehicle.RailInfo.Cardinal;
import dev.botalive.core.vehicle.RailInfo.Shape;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy kolejové fyziky minecartu.
 */
class MinecartPhysicsTest {

    private static final int Y = 64;

    /** Syntetická trať. */
    private static final class FakeRails implements RailReader {
        private final Map<Long, RailInfo> rails = new HashMap<>();

        FakeRails put(int x, int z, Shape shape) {
            rails.put(new BlockPos(x, Y, z).asLong(), new RailInfo(shape, false, false));
            return this;
        }

        FakeRails putPowered(int x, int z, Shape shape, boolean powered) {
            rails.put(new BlockPos(x, Y, z).asLong(), new RailInfo(shape, powered, true));
            return this;
        }

        FakeRails putAt(int x, int y, int z, Shape shape) {
            rails.put(new BlockPos(x, y, z).asLong(), new RailInfo(shape, false, false));
            return this;
        }

        @Override
        public RailInfo railAt(BlockPos pos) {
            return rails.get(pos.asLong());
        }
    }

    private static Vec3 cartAt(int x, int z) {
        return new Vec3(x + 0.5, Y + 0.1, z + 0.5);
    }

    @Test
    void napajenaTratZrychliNaStrop() {
        FakeRails rails = new FakeRails();
        for (int x = 0; x <= 60; x++) {
            rails.putPowered(x, 0, Shape.EAST_WEST, true);
        }
        MinecartPhysics cart = new MinecartPhysics(rails, cartAt(0, 0));

        for (int i = 0; i < 60; i++) {
            cart.step();
        }
        assertEquals(0.4, cart.speed(), 0.01, "strop rychlosti vozíku");
        assertTrue(cart.position().x() > 10, "vozík má jet po trati");
        assertFalse(cart.finishedRiding());
    }

    @Test
    void zastaviNaKonciTrati() {
        FakeRails rails = new FakeRails();
        for (int x = 0; x <= 10; x++) {
            rails.putPowered(x, 0, Shape.EAST_WEST, true);
        }
        MinecartPhysics cart = new MinecartPhysics(rails, cartAt(0, 0));

        for (int i = 0; i < 300 && !cart.finishedRiding(); i++) {
            cart.step();
        }
        assertTrue(cart.finishedRiding(), "jízda má skončit na konci trati");
        assertTrue(cart.position().x() <= 11.0, "vozík nesmí přejet konec kolejí");
    }

    @Test
    void zatackaOtociSmerJizdy() {
        FakeRails rails = new FakeRails();
        // Východní trať x 0..5, v x=5 zatáčka na jih, pak jižní trať.
        for (int x = 0; x < 5; x++) {
            rails.putPowered(x, 0, Shape.EAST_WEST, true);
        }
        rails.put(5, 0, Shape.SOUTH_WEST); // spojuje západ (odkud jedeme) a jih
        for (int z = 1; z <= 10; z++) {
            rails.putPowered(5, z, Shape.NORTH_SOUTH, true);
        }
        MinecartPhysics cart = new MinecartPhysics(rails, cartAt(0, 0));

        for (int i = 0; i < 120; i++) {
            cart.step();
        }
        assertEquals(Cardinal.SOUTH, cart.direction(), "vozík má po zatáčce jet na jih");
        assertTrue(cart.position().z() > 2, "vozík má pokračovat jižní tratí");
        assertEquals(5.5, cart.position().x(), 0.01, "vozík má být vycentrovaný na jižní trati");
    }

    @Test
    void nenapajenaNapajeciKolejBrzdi() {
        FakeRails rails = new FakeRails();
        for (int x = 0; x <= 5; x++) {
            rails.putPowered(x, 0, Shape.EAST_WEST, true);
        }
        for (int x = 6; x <= 20; x++) {
            rails.putPowered(x, 0, Shape.EAST_WEST, false); // brzdicí úsek
        }
        MinecartPhysics cart = new MinecartPhysics(rails, cartAt(0, 0));

        for (int i = 0; i < 300 && !cart.finishedRiding(); i++) {
            cart.step();
        }
        assertTrue(cart.finishedRiding(), "vozík má na brzdách zastavit");
        assertTrue(cart.position().x() < 12, "brzdy mají vozík zastavit brzy");
    }

    @Test
    void sjezdZeSvahuZrychluje() {
        FakeRails rails = new FakeRails();
        // Svah dolů směrem na východ: y klesá z Y+2 na Y.
        rails.putAt(0, Y + 2, 0, Shape.ASCENDING_WEST);  // stoupá k západu = klesá k východu
        rails.putAt(1, Y + 1, 0, Shape.ASCENDING_WEST);
        for (int x = 2; x <= 30; x++) {
            rails.putAt(x, Y, 0, Shape.EAST_WEST);
        }
        MinecartPhysics cart = new MinecartPhysics(rails, new Vec3(0.5, Y + 2.1, 0.5));

        double initialSpeed = cart.speed();
        for (int i = 0; i < 30; i++) {
            cart.step();
        }
        assertTrue(cart.speed() > initialSpeed, "sjezd má vozík zrychlit");
        assertTrue(cart.position().y() < Y + 1.5, "vozík má sjet dolů");
        assertEquals(Cardinal.EAST, cart.direction());
    }

    @Test
    void bezKolejeKonstruktorSelze() {
        assertThrows(IllegalStateException.class,
                () -> new MinecartPhysics(new FakeRails(), cartAt(0, 0)));
    }
}
