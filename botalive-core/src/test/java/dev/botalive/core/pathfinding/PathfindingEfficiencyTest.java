package dev.botalive.core.pathfinding;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresní stráž efektivity A*: memo cache traits drží počet dotazů do světa
 * na jednotkách na expandovaný uzel (před memoizací to bylo ~200 – každou
 * buňku se ptalo až 8 sousedních expanzí znovu). Bez měření času, aby test
 * nebyl flaky – počty dotazů jsou deterministické.
 */
class PathfindingEfficiencyTest {

    @Test
    void memoDrziDotazyDoSvetaNizko() {
        FakeWorldView base = new FakeWorldView(63);
        // Bludiště: zdi napříč s dírou střídavě u krajů – cesta kličkuje
        // a A* expanduje tisíce uzlů.
        int i = 0;
        for (int x = 5; x <= 60; x += 7, i++) {
            int hole = (i % 2 == 0) ? 25 : -25;
            for (int z = -30; z <= 30; z++) {
                if (z != hole && z != hole + 1) {
                    base.wall(x, 64, 66, z);
                }
            }
        }
        AtomicLong queries = new AtomicLong();
        WorldView counting = new WorldView() {
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
                queries.incrementAndGet();
                return base.traitsAt(pos);
            }

            @Override
            public boolean isAvailable(BlockPos pos) {
                return true;
            }

            @Override
            public void prefetch(BlockPos center, int radiusChunks) {
            }

            @Override
            public String worldName() {
                return "bench";
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
        AStarPathfinder.Result result = new AStarPathfinder(counting)
                .findPath(new BlockPos(0, 64, 0), new BlockPos(64, 64, 0), 0, 0L, null);

        assertTrue(result.path().complete(), "bludiště má řešení");
        assertTrue(result.expandedNodes() > 1_000,
                "scénář má vynutit skutečné hledání, expandováno: " + result.expandedNodes());
        double perNode = (double) queries.get() / result.expandedNodes();
        assertTrue(perNode < 10,
                "memo cache má držet dotazy na ~jednotkách na uzel, je %.1f (%d dotazů / %d uzlů)"
                        .formatted(perNode, queries.get(), result.expandedNodes()));
    }
}
