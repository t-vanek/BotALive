package dev.botalive.core.build;

import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy plánu domku – geometrie, dveře, pořadí stavby a natočení.
 */
class HouseBlueprintTest {

    private static final BlockPos ORIGIN = new BlockPos(100, 64, 100);

    @ParameterizedTest
    @EnumSource(HouseFacing.class)
    void dverniOtvorZustavaVolny(HouseFacing facing) {
        List<BlockPos> placements = HouseBlueprint.placements(ORIGIN, facing);
        BlockPos doorBottom = HouseBlueprint.doorBottom(ORIGIN, facing);
        BlockPos doorTop = doorBottom.up();
        assertFalse(placements.contains(doorBottom), "spodek dveří musí zůstat volný");
        assertFalse(placements.contains(doorTop), "vršek dveří musí zůstat volný");
    }

    @ParameterizedTest
    @EnumSource(HouseFacing.class)
    void dvereJsouNaHraneSveOrientace(HouseFacing facing) {
        BlockPos door = HouseBlueprint.doorBottom(ORIGIN, facing);
        switch (facing) {
            case NORTH -> assertEquals(ORIGIN.z(), door.z());
            case SOUTH -> assertEquals(ORIGIN.z() + HouseBlueprint.SIZE - 1, door.z());
            case WEST -> assertEquals(ORIGIN.x(), door.x());
            case EAST -> assertEquals(ORIGIN.x() + HouseBlueprint.SIZE - 1, door.x());
            default -> throw new IllegalStateException();
        }
    }

    @Test
    void pocetBlokuSedi() {
        // Obvod 4×4 = 12 sloupců × 3 vrstvy − 2 dveře + střecha 16.
        assertEquals(12 * 3 - 2 + 16, HouseBlueprint.blocksNeeded());
    }

    @ParameterizedTest
    @EnumSource(HouseFacing.class)
    void pocetBlokuNezavisiNaOrientaci(HouseFacing facing) {
        assertEquals(HouseBlueprint.blocksNeeded(),
                HouseBlueprint.placements(ORIGIN, facing).size());
    }

    @ParameterizedTest
    @EnumSource(HouseFacing.class)
    void zadneDuplicity(HouseFacing facing) {
        List<BlockPos> placements = HouseBlueprint.placements(ORIGIN, facing);
        Set<BlockPos> unique = new HashSet<>(placements);
        assertEquals(placements.size(), unique.size());
    }

    @ParameterizedTest
    @EnumSource(HouseFacing.class)
    void vsechnyBlokyLeziVPudorysu(HouseFacing facing) {
        for (BlockPos pos : HouseBlueprint.placements(ORIGIN, facing)) {
            assertTrue(pos.x() >= ORIGIN.x() && pos.x() < ORIGIN.x() + HouseBlueprint.SIZE,
                    "blok mimo půdorys: " + pos);
            assertTrue(pos.z() >= ORIGIN.z() && pos.z() < ORIGIN.z() + HouseBlueprint.SIZE,
                    "blok mimo půdorys: " + pos);
        }
    }

    @Test
    void staviSeZdolaNahoru() {
        List<BlockPos> placements = HouseBlueprint.placements(ORIGIN, HouseFacing.NORTH);
        int lastY = Integer.MIN_VALUE;
        for (BlockPos pos : placements) {
            assertTrue(pos.y() >= lastY, "bloky se pokládají zdola nahoru");
            lastY = pos.y();
        }
    }

    @Test
    void strechaAzPoZdech() {
        List<BlockPos> placements = HouseBlueprint.placements(ORIGIN, HouseFacing.NORTH);
        int firstRoof = -1;
        for (int i = 0; i < placements.size(); i++) {
            if (placements.get(i).y() == ORIGIN.y() + HouseBlueprint.WALL_HEIGHT) {
                firstRoof = i;
                break;
            }
        }
        assertTrue(firstRoof >= 12 * 3 - 2, "střecha se staví až po dokončení zdí");
    }

    @ParameterizedTest
    @EnumSource(HouseFacing.class)
    void mistoProStaniJeUvnitr(HouseFacing facing) {
        BlockPos stand = HouseBlueprint.standPoint(ORIGIN, facing);
        assertTrue(stand.x() > ORIGIN.x() && stand.x() < ORIGIN.x() + HouseBlueprint.SIZE - 1);
        assertTrue(stand.z() > ORIGIN.z() && stand.z() < ORIGIN.z() + HouseBlueprint.SIZE - 1);
        assertFalse(HouseBlueprint.placements(ORIGIN, facing).contains(stand),
                "bot nesmí stavět blok na místě, kde stojí");
    }

    @ParameterizedTest
    @EnumSource(HouseFacing.class)
    void nabytekJeUvnitr(HouseFacing facing) {
        for (BlockPos spot : List.of(HouseBlueprint.torchSpot(ORIGIN, facing),
                HouseBlueprint.bedSpot(ORIGIN, facing))) {
            assertTrue(spot.x() > ORIGIN.x() && spot.x() < ORIGIN.x() + HouseBlueprint.SIZE - 1,
                    "nábytek musí být uvnitř: " + spot);
            assertTrue(spot.z() > ORIGIN.z() && spot.z() < ORIGIN.z() + HouseBlueprint.SIZE - 1,
                    "nábytek musí být uvnitř: " + spot);
        }
    }

    @Test
    void podlahaPokryvaCelyPudorys() {
        assertEquals(HouseBlueprint.SIZE * HouseBlueprint.SIZE,
                HouseBlueprint.groundColumns(ORIGIN).size());
    }

    @Test
    void orientaceKCiliMiriSpravne() {
        // Dům západně od návsi má koukat na východ atd.
        assertEquals(HouseFacing.EAST, HouseFacing.toward(0, 0, 10, 3));
        assertEquals(HouseFacing.WEST, HouseFacing.toward(10, 3, 0, 0));
        assertEquals(HouseFacing.SOUTH, HouseFacing.toward(0, 0, 3, 10));
        assertEquals(HouseFacing.NORTH, HouseFacing.toward(3, 10, 0, 0));
    }
}
