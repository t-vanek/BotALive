package dev.botalive.core.pathfinding;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.BotRandom;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Ruční benchmark jádra A* pro rozhodování o optimalizacích (v3.4 gate:
 * „bez měření neimplementovat"). Neběží v CI (čas ~15 s a měření času je
 * na sdíleném stroji flaky) – odkomentovat {@code @Disabled} a spustit:
 * {@code gradle :botalive-core:test --tests '*PerfBenchTest'}; výstup je
 * v system-out test reportu.
 *
 * <p>Poslední měření (2026-07, kontejner): bludiště 6 665 uzlů / 26 ms
 * (~252 uzlů/ms), pláň 61 uzlů / 0,19 ms, členitý terén s nedosažitelným
 * cílem 8 000 uzlů (strop) / 41 ms. Rozklad: alokace ~2 %, prioritní
 * fronta ~12 %, zbytek memo dotazy a pochozí výšky bez dominantního
 * hotspotu – bucket queue ani long-přepis smyčky se nevyplatí (riziko
 * čitelnosti/tie-breaku za ~10 % zisku). Časový rozpočet 25 ms správně
 * ořezává adversariální případy; nejhorší vzor „nedosažitelný cíl pálí
 * celý rozpočet" řeší near/anyNear migrace goalů.</p>
 */
@Disabled("ruční benchmark – spouštět lokálně při ladění výkonu")
class PerfBenchTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    @Test
    void bench() {
        // 1) Bludiště (adversariální – h skoro k ničemu).
        FakeWorldView maze = new FakeWorldView(FLOOR);
        int i = 0;
        for (int x = 5; x <= 60; x += 7, i++) {
            int hole = (i % 2 == 0) ? 25 : -25;
            for (int z = -30; z <= 30; z++) {
                if (z != hole && z != hole + 1) {
                    maze.wall(x, 64, 66, z);
                }
            }
        }
        // 2) Otevřená pláň (h těsné, dlouhá vzdálenost).
        FakeWorldView plain = new FakeWorldView(FLOOR);
        // 3) Členitý terén (náhodné kopce, jako sim seedy).
        FakeWorldView rough = new FakeWorldView(FLOOR);
        BotRandom rng = new BotRandom(42);
        for (int x = -5; x <= 70; x++) {
            for (int z = -20; z <= 20; z++) {
                int h = (int) (Math.sin(x * 0.35) * 2 + Math.cos(z * 0.5) * 2 + rng.rangeInt(0, 2));
                for (int y = 0; y < h; y++) {
                    rough.set(x, FEET + y, z, FakeWorldView.SOLID);
                }
            }
        }

        scenario("maze  ", maze, new BlockPos(0, 64, 0), new BlockPos(64, 64, 0));
        scenario("plain ", plain, new BlockPos(0, FEET, 0), new BlockPos(60, FEET, 25));
        scenario("rough ", rough, new BlockPos(0, FEET + surface(rough, 0, 0), 0),
                new BlockPos(60, FEET, 10));
    }

    private static int surface(FakeWorldView w, int x, int z) {
        int h = 0;
        while (w.traitsAt(new BlockPos(x, FEET + h, z)).solid()) {
            h++;
        }
        return h;
    }

    private void scenario(String name, FakeWorldView world, BlockPos start, BlockPos goal) {
        // Warm-up (JIT).
        for (int w = 0; w < 60; w++) {
            new AStarPathfinder(world).findPath(start, goal, 0, 0L, null);
        }
        int runs = 150;
        long best = Long.MAX_VALUE;
        long total = 0;
        long nodes = 0;
        boolean complete = false;
        for (int r = 0; r < runs; r++) {
            long t0 = System.nanoTime();
            AStarPathfinder.Result res = new AStarPathfinder(world)
                    .findPath(start, goal, 0, 0L, null);
            long dt = System.nanoTime() - t0;
            total += dt;
            best = Math.min(best, dt);
            nodes += res.expandedNodes();
            complete = res.path().complete();
        }
        double avgMs = total / 1e6 / runs;
        double bestMs = best / 1e6;
        long avgNodes = nodes / runs;
        System.out.printf("%s complete=%b nodes=%d avg=%.3fms best=%.3fms nodes/ms=%.0f%n",
                name, complete, avgNodes, avgMs, bestMs, avgNodes / avgMs);
    }
}
