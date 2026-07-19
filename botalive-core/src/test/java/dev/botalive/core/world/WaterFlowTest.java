package dev.botalive.core.world;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Směr proudu z gradientu hladin (vanilla mechanika zjednodušeně).
 */
class WaterFlowTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    @Test
    void zdrojovaTunNemaProud() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int x = 2; x <= 6; x++) {
            world.set(x, FEET, 0, FakeWorldView.WATER);
        }
        assertEquals(Vec3.ZERO, WaterFlow.at(world::traitsAt, new BlockPos(4, FEET, 0)));
    }

    @Test
    void proudTeceOdZdrojeKTencimuKonci() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Zdroj na x=2, hladiny řídnou směrem +x → proud míří na +x.
        world.set(2, FEET, 0, FakeWorldView.WATER);
        for (int x = 3; x <= 8; x++) {
            world.set(x, FEET, 0, FakeWorldView.flowing(x - 2));
        }
        Vec3 flow = WaterFlow.at(world::traitsAt, new BlockPos(5, FEET, 0));
        assertTrue(flow.x() > 0.9, "proud má mířit po gradientu k tenčí vodě: " + flow);
        assertEquals(0, flow.z(), 1.0E-6);
    }

    @Test
    void hranaVodopaduTahnePresOkraj() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Voda na hraně: soused (+x) je vzduch s padajícím sloupcem o patro
        // níž (FLOOR == FEET−1, buňka podlahy je tu nahrazena vodopádem).
        world.set(3, FEET, 0, FakeWorldView.flowing(2));
        world.set(4, FEET - 1, 0, FakeWorldView.flowing(8));
        Vec3 flow = WaterFlow.at(world::traitsAt, new BlockPos(3, FEET, 0));
        assertTrue(flow.x() > 0.9, "voda má přepadat přes hranu: " + flow);
    }

    @Test
    void padajiciSloupecTahneDolu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(3, FEET, 0, FakeWorldView.flowing(8));
        Vec3 flow = WaterFlow.at(world::traitsAt, new BlockPos(3, FEET, 0));
        assertTrue(flow.y() < -0.9, "padající sloupec má táhnout dolů: " + flow);
    }
}
