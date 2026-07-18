package dev.botalive.core.pathfinding;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy výběru mezicílů pro dlouhé trasy.
 */
class SegmentPlannerTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    @Test
    void mezicilLeziNaPrimceKCili() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BlockPos from = new BlockPos(0, FEET, 0);
        BlockPos to = new BlockPos(500, FEET, 0);

        BlockPos segment = SegmentPlanner.nextSegment(world, from, to, 64, 0);

        assertNotNull(segment);
        assertEquals(64, segment.x(), "mezicíl má být 64 bloků po přímce");
        assertEquals(0, segment.z());
        assertEquals(FEET, segment.y(), "mezicíl má sedět na povrchu");
    }

    @Test
    void lateralniPosunJdeKolmoNaSmer() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BlockPos from = new BlockPos(0, FEET, 0);
        BlockPos to = new BlockPos(500, FEET, 0);

        BlockPos offset = SegmentPlanner.nextSegment(world, from, to, 64, 24);

        assertNotNull(offset);
        assertEquals(64, offset.x(), "posun nemění vzdálenost po směru");
        assertEquals(24, offset.z(), "posun jde kolmo (u směru +X je to +Z)");
    }

    @Test
    void mezicilNajdePovrchNaKopci() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Kopec: sloupec plných bloků do FEET+9 v místě mezicíle.
        for (int y = FEET; y <= FEET + 9; y++) {
            world.set(64, y, 0, FakeWorldView.SOLID);
        }
        BlockPos segment = SegmentPlanner.nextSegment(world,
                new BlockPos(0, FEET, 0), new BlockPos(500, FEET, 0), 64, 0);

        assertNotNull(segment);
        assertEquals(FEET + 10, segment.y(), "mezicíl má sedět na vršku kopce");
    }

    @Test
    void vodniHladinaJePlatnyMezicil() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Jezero v místě mezicíle (hladina v úrovni FEET).
        for (int y = FEET - 3; y <= FEET; y++) {
            world.set(64, y, 0, FakeWorldView.WATER);
        }
        // Dno jezera pod vodou.
        world.set(64, FEET - 4, 0, FakeWorldView.SOLID);

        BlockPos segment = SegmentPlanner.nextSegment(world,
                new BlockPos(0, FEET, 0), new BlockPos(500, FEET, 0), 64, 0);

        assertNotNull(segment);
        assertEquals(FEET, segment.y(), "mezicílem má být hladina (plave se)");
        assertTrue(world.traitsAt(segment).liquid());
    }

    @Test
    void neznamyChunkVraciNull() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Sloupec mezicíle je nenačtený (studená cache).
        for (int y = FEET - 25; y <= FEET + 25; y++) {
            world.set(64, y, 0, BlockTraits.UNKNOWN);
        }
        BlockPos segment = SegmentPlanner.nextSegment(world,
                new BlockPos(0, FEET, 0), new BlockPos(500, FEET, 0), 64, 0);

        assertNull(segment, "do neznáma se mezicíl neplánuje");
    }

    @Test
    void lavaNaPovrchuVraciNull() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(64, FEET, 0, FakeWorldView.HAZARD);
        BlockPos segment = SegmentPlanner.nextSegment(world,
                new BlockPos(0, FEET, 0), new BlockPos(500, FEET, 0), 64, 0);

        assertNull(segment, "na lávové pole se mezicíl neplánuje");
    }
}
