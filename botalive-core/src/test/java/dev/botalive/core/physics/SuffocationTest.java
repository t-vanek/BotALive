package dev.botalive.core.physics;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Testy detekce zazdění (bot uvnitř pevného bloku se dusí).
 */
class SuffocationTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    @Test
    void zazdenyCelymTelemHlasiBlokHlavy() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(0, FEET, 0, FakeWorldView.SOLID);
        world.set(0, FEET + 1, 0, FakeWorldView.SOLID);

        BlockPos trapped = Suffocation.trappedIn(world, new Vec3(0.5, FEET, 0.5));
        assertEquals(new BlockPos(0, FEET + 1, 0), trapped,
                "prioritou je blok v úrovni očí (dusí)");
    }

    @Test
    void zasypanyJenVNohouHlasiNohy() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(0, FEET, 0, FakeWorldView.SOLID);

        BlockPos trapped = Suffocation.trappedIn(world, new Vec3(0.5, FEET, 0.5));
        assertEquals(new BlockPos(0, FEET, 0), trapped);
    }

    @Test
    void volnyBotNeniZazdeny() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        assertNull(Suffocation.trappedIn(world, new Vec3(0.5, FEET, 0.5)));
    }

    @Test
    void staniNaDesceNeniZazdeni() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(0, FEET, 0, FakeWorldView.SLAB_BOTTOM);
        // Nohy na horní ploše desky (+0,5) – tělo je NAD kolizí, ne v ní.
        assertNull(Suffocation.trappedIn(world, new Vec3(0.5, FEET + 0.5, 0.5)));
    }

    @Test
    void nenactenyChunkNeniZazdeni() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(0, FEET, 0, dev.botalive.core.world.BlockTraits.UNKNOWN);
        world.set(0, FEET + 1, 0, dev.botalive.core.world.BlockTraits.UNKNOWN);
        assertNull(Suffocation.trappedIn(world, new Vec3(0.5, FEET, 0.5)),
                "neznámo se nekope – jen se čeká na data");
    }
}
