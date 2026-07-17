package dev.botalive.core.build;

import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy plánu domku – geometrie, dveře, pořadí stavby.
 */
class HouseBlueprintTest {

    private static final BlockPos ORIGIN = new BlockPos(100, 64, 100);

    @Test
    void dvernOtvorZustavaVolny() {
        List<BlockPos> placements = HouseBlueprint.placements(ORIGIN);
        BlockPos doorBottom = ORIGIN.offset(HouseBlueprint.DOOR_X, 0, 0);
        BlockPos doorTop = ORIGIN.offset(HouseBlueprint.DOOR_X, 1, 0);
        assertFalse(placements.contains(doorBottom), "spodek dveří musí zůstat volný");
        assertFalse(placements.contains(doorTop), "vršek dveří musí zůstat volný");
    }

    @Test
    void pocetBlokuSedi() {
        // Obvod 4×4 = 12 sloupců × 3 vrstvy − 2 dveře + střecha 16.
        assertEquals(12 * 3 - 2 + 16, HouseBlueprint.blocksNeeded());
    }

    @Test
    void zadneDuplicity() {
        List<BlockPos> placements = HouseBlueprint.placements(ORIGIN);
        Set<BlockPos> unique = new HashSet<>(placements);
        assertEquals(placements.size(), unique.size());
    }

    @Test
    void staviSeZdolaNahoru() {
        List<BlockPos> placements = HouseBlueprint.placements(ORIGIN);
        int lastY = Integer.MIN_VALUE;
        for (BlockPos pos : placements) {
            assertTrue(pos.y() >= lastY, "bloky se pokládají zdola nahoru");
            lastY = pos.y();
        }
    }

    @Test
    void strechaAzPoZdech() {
        List<BlockPos> placements = HouseBlueprint.placements(ORIGIN);
        int firstRoof = -1;
        for (int i = 0; i < placements.size(); i++) {
            if (placements.get(i).y() == ORIGIN.y() + HouseBlueprint.WALL_HEIGHT) {
                firstRoof = i;
                break;
            }
        }
        assertTrue(firstRoof >= 12 * 3 - 2, "střecha se staví až po dokončení zdí");
    }

    @Test
    void mistoProStaniJeUvnitr() {
        BlockPos stand = HouseBlueprint.standPoint(ORIGIN);
        assertTrue(stand.x() > ORIGIN.x() && stand.x() < ORIGIN.x() + HouseBlueprint.SIZE - 1);
        assertTrue(stand.z() > ORIGIN.z() && stand.z() < ORIGIN.z() + HouseBlueprint.SIZE - 1);
        assertFalse(HouseBlueprint.placements(ORIGIN).contains(stand),
                "bot nesmí stavět blok na místě, kde stojí");
    }

    @Test
    void podlahaPokryvaCelyPudorys() {
        assertEquals(HouseBlueprint.SIZE * HouseBlueprint.SIZE,
                HouseBlueprint.groundColumns(ORIGIN).size());
    }
}
