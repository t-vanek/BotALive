package dev.botalive.core.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy vektorové matematiky a pozic bloků.
 */
class VecMathTest {

    @Test
    void normalizaceDavaJednotkovouDelku() {
        Vec3 vector = new Vec3(3, 4, 0).normalized();
        assertEquals(1.0, vector.length(), 1e-9);
    }

    @Test
    void normalizaceNulovehoVektoruJeNulova() {
        assertEquals(Vec3.ZERO, Vec3.ZERO.normalized());
    }

    @Test
    void prevodNaBlokZaokrouhlujeDolu() {
        assertEquals(new BlockPos(1, 64, -3), new Vec3(1.9, 64.2, -2.1).toBlockPos());
        assertEquals(new BlockPos(-1, 63, 0), new Vec3(-0.1, 63.9, 0.5).toBlockPos());
    }

    @Test
    void asLongJeUnikatniProRuznePozice() {
        Set<Long> keys = new HashSet<>();
        for (int x = -20; x <= 20; x += 5) {
            for (int y = -60; y <= 300; y += 40) {
                for (int z = -20; z <= 20; z += 5) {
                    assertTrue(keys.add(new BlockPos(x, y, z).asLong()),
                            "kolize klíče pro " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void manhattanVzdalenost() {
        assertEquals(9, new BlockPos(0, 0, 0).manhattan(new BlockPos(2, 3, 4)));
    }

    @Test
    void stredBlokuJeUprostred() {
        Vec3 center = new BlockPos(2, 64, -3).center();
        assertEquals(2.5, center.x(), 1e-9);
        assertEquals(64.0, center.y(), 1e-9);
        assertEquals(-2.5, center.z(), 1e-9);
    }
}
