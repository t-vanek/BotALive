package dev.botalive.core.settlement;

import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.build.HouseFacing;
import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy rozložení parcel – prstence, rozestupy, orientace k návsi.
 */
class PlotLayoutTest {

    private static final BlockPos CENTER = new BlockPos(0, 64, 0);
    private static final int SPACING = 12;

    @Test
    void prvniPrstenecMaOsmBunek() {
        for (int index = 1; index <= 8; index++) {
            int[] cell = PlotLayout.cellFor(index);
            assertEquals(1, Math.max(Math.abs(cell[0]), Math.abs(cell[1])),
                    "index " + index + " patří do prstence 1");
        }
        int[] ninth = PlotLayout.cellFor(9);
        assertEquals(2, Math.max(Math.abs(ninth[0]), Math.abs(ninth[1])),
                "index 9 patří do prstence 2");
    }

    @Test
    void bunkyJsouUnikatni() {
        Set<Long> seen = new HashSet<>();
        for (int index = 1; index <= 224; index++) {
            int[] cell = PlotLayout.cellFor(index);
            long key = ((long) cell[0] << 32) | (cell[1] & 0xFFFFFFFFL);
            assertTrue(seen.add(key), "duplicitní buňka pro index " + index);
        }
    }

    @Test
    void parcelySeNeprekryvaji() {
        // Sousední buňky jsou spacing od sebe; dům 4×4 → mezera 8 bloků.
        BlockPos a = PlotLayout.plotOrigin(CENTER, 1, SPACING);
        BlockPos b = PlotLayout.plotOrigin(CENTER, 2, SPACING);
        int gap = Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z()));
        assertTrue(gap >= SPACING, "rozestup origin bodů musí být aspoň spacing");
    }

    @Test
    void parcelaNekolidujeSNavsi() {
        // Náves (zakladatelův dům, střed ±2) nesmí zasahovat do parcel prstence 1.
        for (int index = 1; index <= 8; index++) {
            BlockPos origin = PlotLayout.plotOrigin(CENTER, index, SPACING);
            boolean overlapsCenter = origin.x() <= CENTER.x() + 2
                    && origin.x() + HouseBlueprint.SIZE - 1 >= CENTER.x() - 2
                    && origin.z() <= CENTER.z() + 2
                    && origin.z() + HouseBlueprint.SIZE - 1 >= CENTER.z() - 2;
            assertTrue(!overlapsCenter, "parcela " + index + " koliduje s návsí");
        }
    }

    @Test
    void dumSeDivaKNavsi() {
        // Parcela východně od středu má mít dveře na západ.
        BlockPos east = new BlockPos(SPACING - 2, 64, -2);
        assertEquals(HouseFacing.WEST, PlotLayout.facingToward(east, CENTER));
        BlockPos north = new BlockPos(-2, 64, -SPACING - 2);
        assertEquals(HouseFacing.SOUTH, PlotLayout.facingToward(north, CENTER));
    }
}
