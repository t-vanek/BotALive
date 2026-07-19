package dev.botalive.core.pathfinding;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy hrubého koridorového plánovače dálkových tras.
 */
class FarPlannerTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    @Test
    void rovnaKrajinaVedeKCili() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        FarPlanner.Corridor corridor = FarPlanner.plan(world,
                new BlockPos(0, FEET, 0), new BlockPos(200, FEET, 0));

        assertTrue(corridor.complete(), "po rovině má koridor dosáhnout cíle");
        assertFalse(corridor.isEmpty());
        BlockPos last = corridor.points().getLast();
        assertTrue(Math.abs(last.x() - 200) <= FarPlanner.CELL,
                "koridor má končit u cíle: " + last);
    }

    @Test
    void lavovePoleObejde() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Lávové pole napříč přímou trasou (x 20..60, z −100..24) – suchá
        // obchůzka existuje jen severně (z > 24).
        for (int x = 20; x <= 60; x++) {
            for (int z = -100; z <= 24; z++) {
                world.set(x, FEET, z, FakeWorldView.HAZARD);
                world.set(x, FLOOR, z, FakeWorldView.HAZARD);
            }
        }
        FarPlanner.Corridor corridor = FarPlanner.plan(world,
                new BlockPos(0, FEET, 0), new BlockPos(100, FEET, 0));

        assertTrue(corridor.complete(), "obchůzka existuje – koridor ji má najít");
        for (BlockPos point : corridor.points()) {
            assertFalse(world.traitsAt(point).hazard()
                            || world.traitsAt(point.down()).hazard(),
                    "bod koridoru nesmí ležet v lávě: " + point);
        }
        assertTrue(corridor.points().stream().anyMatch(p -> p.z() > 24),
                "obchůzka má vést severně kolem pole: " + corridor.points());
    }

    @Test
    void vodaJePruchoziAleDrazsi() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Vodní pás napříč (x 20..28) – bez suché obchůzky se plave.
        for (int x = 20; x <= 28; x++) {
            for (int z = -120; z <= 120; z++) {
                world.set(x, FEET, z, FakeWorldView.WATER);
                world.set(x, FLOOR, z, FakeWorldView.WATER);
            }
        }
        FarPlanner.Corridor corridor = FarPlanner.plan(world,
                new BlockPos(0, FEET, 0), new BlockPos(100, FEET, 0));

        assertTrue(corridor.complete(), "voda je průchozí (plave se)");
        assertTrue(corridor.points().stream()
                        .anyMatch(p -> world.traitsAt(p).liquid()),
                "koridor má vést přes vodní pás");
    }

    @Test
    void vycerpanyRozpocetVraciCastecnyKoridor() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Cíl daleko za rozpočtem expanzí – koridor má být částečný a mířit
        // správným směrem (bot se přiblíží a přepočítá).
        FarPlanner.Corridor corridor = FarPlanner.plan(world,
                new BlockPos(0, FEET, 0), new BlockPos(40_000, FEET, 0));

        assertFalse(corridor.complete(), "tak daleko rozpočet nedosáhne");
        assertFalse(corridor.isEmpty(), "částečný koridor má aspoň přiblížit");
        assertTrue(corridor.points().getLast().x() > 1_000,
                "částečný koridor má postoupit k cíli: " + corridor.points().getLast());
    }

    @Test
    void utesSPruchodemNajdeProchod() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Stejný masiv, ale s průsmykem na z 60..70.
        for (int x = 40; x <= 56; x++) {
            for (int z = -300; z <= 300; z++) {
                if (z < 60 || z > 70) {
                    world.wall(x, FEET, FEET + 19, z);
                }
            }
        }
        FarPlanner.Corridor corridor = FarPlanner.plan(world,
                new BlockPos(0, FEET, 0), new BlockPos(120, FEET, 0));

        assertTrue(corridor.complete(), "průsmykem má koridor projít");
        assertTrue(corridor.points().stream()
                        .anyMatch(p -> p.x() >= 40 && p.x() <= 56 && p.z() >= 55 && p.z() <= 75),
                "koridor má vést průsmykem: " + corridor.points());
    }

    @Test
    void nenacteneChunkyProjdeOptimisticky() {
        FakeWorldView base = new FakeWorldView(FLOOR);
        // Za x > 40 svět „není načtený" (UNKNOWN + isAvailable false).
        WorldView cold = new WorldView() {
            @Override
            public org.bukkit.Material materialAt(BlockPos pos) {
                return base.materialAt(pos);
            }

            @Override
            public org.bukkit.block.data.BlockData blockDataAt(BlockPos pos) {
                return base.blockDataAt(pos);
            }

            @Override
            public BlockTraits traitsAt(BlockPos pos) {
                return pos.x() > 40 ? BlockTraits.UNKNOWN : base.traitsAt(pos);
            }

            @Override
            public boolean isAvailable(BlockPos pos) {
                return pos.x() <= 40;
            }

            @Override
            public void prefetch(BlockPos center, int radiusChunks) {
            }

            @Override
            public String worldName() {
                return "cold";
            }

            @Override
            public WorldDimension dimension() {
                return WorldDimension.OVERWORLD;
            }

            @Override
            public int minY() {
                return -64;
            }
        };
        FarPlanner.Corridor corridor = FarPlanner.plan(cold,
                new BlockPos(0, FEET, 0), new BlockPos(160, FEET, 0));

        assertTrue(corridor.complete(),
                "nenačtený terén je optimisticky průchozí – bot smí vyrazit směrem k cíli");
        assertTrue(corridor.points().stream().anyMatch(p -> p.x() > 100),
                "koridor má pokračovat i přes nenačtenou oblast");
    }
}
