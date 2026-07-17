package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronní výpočet cest – sdílený thread pool pro všechny boty.
 *
 * <p>A* nad velkou oblastí může trvat jednotky milisekund; tick vlákna botů
 * ani herní vlákna se tím nesmí zdržovat. Cíle si vyžádají cestu, pokračují
 * v činnosti a výsledek si vyzvednou v některém z dalších ticků.</p>
 */
public final class NavigationService {

    private static final Logger LOG = LoggerFactory.getLogger(NavigationService.class);

    private final ExecutorService executor;

    /**
     * @param configuredThreads vlákna z konfigurace; 0 = auto (⅛ CPU, min 1)
     */
    public NavigationService(int configuredThreads) {
        int threads = configuredThreads > 0
                ? configuredThreads
                : Math.max(1, Runtime.getRuntime().availableProcessors() / 8);
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "BotAlive-Pathfinder-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        LOG.info("Pathfinding pool spuštěn s {} vlákny", threads);
    }

    /**
     * Naplánuje cestu asynchronně.
     *
     * @param world      pohled na svět
     * @param start      startovní blok
     * @param goal       cílový blok
     * @param nodeBudget rozpočet uzlů (0 = default)
     * @return future s cestou (nikdy neselže – při chybě vrací prázdnou cestu)
     */
    public CompletableFuture<Path> findPath(WorldView world, BlockPos start, BlockPos goal, int nodeBudget) {
        return findPath(world, start, goal, nodeBudget, java.util.List.of());
    }

    /**
     * Naplánuje cestu s vyhýbáním se špatným vzpomínkám bota.
     *
     * @param world      pohled na svět
     * @param start      startovní blok
     * @param goal       cílový blok
     * @param nodeBudget rozpočet uzlů (0 = default)
     * @param dangers    místa smrtí/nebezpečí z paměti bota (může být prázdné)
     * @return future s cestou (nikdy neselže – při chybě vrací prázdnou cestu)
     */
    public CompletableFuture<Path> findPath(WorldView world, BlockPos start, BlockPos goal,
                                            int nodeBudget, java.util.List<BlockPos> dangers) {
        // Prefetch okolí, ať má A* s čím pracovat, než se pustí do výpočtu.
        world.prefetch(start, 2);
        world.prefetch(goal, 1);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new AStarPathfinder(world, dangers).findPath(start, goal, nodeBudget);
            } catch (Throwable t) {
                LOG.warn("Pathfinding selhal ({} -> {}): {}", start, goal, t.toString());
                return new Path(java.util.List.of(), false);
            }
        }, executor);
    }

    /** Zastaví pool (vypnutí pluginu). */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
