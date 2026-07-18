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
                new Vec3(0.5, FEET, 0.5), 0, world);
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
                new Vec3(0.5, FEET, 0.5), 0, world);
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
                new Vec3(0.5, FEET, 0.5), 0, world);
        assertTrue(out.jump(), "v lávě se drží skok (stoupání)");
        assertTrue(out.direction().x() > 0.5,
                "útěk má mířit k nejbližšímu bezpečí na východě: " + out.direction());
    }

    @Test
    void dochazejiciDechPrebijiNavigaci() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Utěsněná zatopená jeskyně: obvodové zdivo a strop, uvnitř chodba
        // plná vody. Jediný vzduch je kapsa na východě (x=4) – hladina
        // s volnou hlavou. Kapsa NAD stropem být nesmí (nedoplavatelná).
        for (int x = -4; x <= 5; x++) {
            for (int z = -3; z <= 3; z++) {
                world.wall(x, FEET, FEET + 3, z);
            }
        }
        for (int x = -3; x <= 3; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = FEET; y <= FEET + 2; y++) {
                    world.set(x, y, z, FakeWorldView.WATER);
                }
            }
        }
        for (int z = -1; z <= 1; z++) {
            world.set(4, FEET, z, FakeWorldView.WATER);      // hladina kapsy
            world.set(4, FEET + 1, z, FakeWorldView.AIRLIKE); // vzduch na nádech
            world.set(4, FEET + 2, z, FakeWorldView.AIRLIKE);
        }
        // Navigace si tvrdohlavě plave na západ – ale dech dochází.
        MoveInput diving = MoveInput.of(new Vec3(-1, 0, 0), false, false);
        MoveInput out = LiquidReflex.apply(diving, true,
                new Vec3(0.5, FEET, 0.5), 200, world);
        assertTrue(out.jump(), "při dušení se drží skok");
        assertTrue(out.direction().x() > 0.5,
                "dušení má přebít navigaci a mířit ke vzduchové kapse: " + out.direction());
    }

    @Test
    void sDostatkemDechuNavigacePlaveDal() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int y = FEET; y <= FEET + 3; y++) {
            world.set(0, y, 0, FakeWorldView.WATER);
        }
        MoveInput diving = MoveInput.of(new Vec3(-1, 0, 0), false, false);
        MoveInput out = LiquidReflex.apply(diving, true,
                new Vec3(0.5, FEET, 0.5), 60, world);
        assertFalse(out.jump(), "s plnými plícemi se záměrné potápění nepřebíjí");
    }

    @Test
    void naSuchuNechavaVstupBezeZmeny() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        MoveInput walking = MoveInput.of(new Vec3(0, 0, 1), true, false);
        MoveInput out = LiquidReflex.apply(walking, true,
                new Vec3(0.5, FEET, 0.5), 0, world);
        assertEquals(walking, out, "na suchu reflex do vstupu nezasahuje");
    }
}
