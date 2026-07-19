package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronní výpočet cest – sdílený thread pool pro všechny boty.
 *
 * <p>A* nad velkou oblastí může trvat jednotky milisekund; tick vlákna botů
 * ani herní vlákna se tím nesmí zdržovat. Cíle si vyžádají cestu, pokračují
 * v činnosti a výsledek si vyzvednou v některém z dalších ticků.</p>
 *
 * <p>Výpočty respektují konfigurovaný rozpočet uzlů a časový strop
 * (sekce {@code pathfinding.*}), jsou kooperativně zrušitelné přes
 * {@link PathRequest#cancel()} a jejich průběh se agreguje do
 * {@link PathfindingStats} (viz {@code /botalive path}).</p>
 */
public final class NavigationService {

    private static final Logger LOG = LoggerFactory.getLogger(NavigationService.class);

    private final ExecutorService executor;
    /** Výchozí rozpočet uzlů; {@code <= 0} = default pathfinderu. */
    private final int nodeBudget;
    /** Časový strop výpočtu (ms); {@code <= 0} = bez limitu. */
    private final long timeBudgetMs;
    /** Hrubé koridory pro dálkové trasy ({@link FarPlanner}). */
    private final boolean farCorridors;
    /** Kopací hrany jako náhrada assist eskalace (kill-switch). */
    private final boolean plannedActions;
    private final PathfindingStats stats = new PathfindingStats();

    /**
     * @param configuredThreads vlákna z konfigurace; 0 = auto (⅛ CPU, min 1)
     */
    public NavigationService(int configuredThreads) {
        this(configuredThreads, 0, 0L, true);
    }

    /**
     * @param configuredThreads vlákna z konfigurace; 0 = auto (⅛ CPU, min 1)
     * @param nodeBudget        rozpočet uzlů jednoho výpočtu; {@code <= 0} default
     * @param timeBudgetMs      časový strop výpočtu (ms); {@code <= 0} bez limitu
     */
    public NavigationService(int configuredThreads, int nodeBudget, long timeBudgetMs) {
        this(configuredThreads, nodeBudget, timeBudgetMs, true);
    }

    /**
     * @param configuredThreads vlákna z konfigurace; 0 = auto (⅛ CPU, min 1)
     * @param nodeBudget        rozpočet uzlů jednoho výpočtu; {@code <= 0} default
     * @param timeBudgetMs      časový strop výpočtu (ms); {@code <= 0} bez limitu
     * @param farCorridors      hrubé koridory pro dálkové trasy (kill-switch)
     */
    public NavigationService(int configuredThreads, int nodeBudget, long timeBudgetMs,
                             boolean farCorridors) {
        this(configuredThreads, nodeBudget, timeBudgetMs, farCorridors, true);
    }

    /**
     * @param configuredThreads vlákna z konfigurace; 0 = auto (⅛ CPU, min 1)
     * @param nodeBudget        rozpočet uzlů jednoho výpočtu; {@code <= 0} default
     * @param timeBudgetMs      časový strop výpočtu (ms); {@code <= 0} bez limitu
     * @param farCorridors      hrubé koridory pro dálkové trasy (kill-switch)
     * @param plannedActions    kopací hrany jako náhrada assist eskalace (kill-switch)
     */
    public NavigationService(int configuredThreads, int nodeBudget, long timeBudgetMs,
                             boolean farCorridors, boolean plannedActions) {
        this.nodeBudget = nodeBudget;
        this.timeBudgetMs = timeBudgetMs;
        this.farCorridors = farCorridors;
        this.plannedActions = plannedActions;
        int threads = configuredThreads > 0
                ? configuredThreads
                : Math.max(1, Runtime.getRuntime().availableProcessors() / 8);
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "BotAlive-Pathfinder-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        LOG.info("Pathfinding pool spuštěn s {} vlákny (rozpočet {} uzlů / {} ms)",
                threads, nodeBudget > 0 ? nodeBudget : "default",
                timeBudgetMs > 0 ? timeBudgetMs : "∞");
    }

    /** @return agregované metriky výpočtů (pro {@code /botalive path}) */
    public PathfindingStats stats() {
        return stats;
    }

    /** @return {@code true} když jsou zapnuté hrubé koridory dálkových tras */
    public boolean farCorridors() {
        return farCorridors;
    }

    /** @return {@code true} když jsou zapnuté kopací hrany (náhrada assistu) */
    public boolean plannedActions() {
        return plannedActions;
    }

    /**
     * Naplánuje hrubý koridor k dalekému cíli asynchronně ({@link FarPlanner}).
     *
     * @param world pohled na svět
     * @param from  startovní blok
     * @param to    cílový blok
     * @return future s koridorem (nikdy neselže – při chybě nese prázdný koridor)
     */
    public CompletableFuture<FarPlanner.Corridor> planCorridor(WorldView world,
                                                               BlockPos from, BlockPos to) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return FarPlanner.plan(world, from, to);
            } catch (Throwable t) {
                LOG.warn("Plánování koridoru selhalo ({} -> {}): {}", from, to, t.toString());
                return FarPlanner.Corridor.EMPTY;
            }
        }, executor);
    }

    /**
     * Naplánuje cestu asynchronně.
     *
     * @param world      pohled na svět
     * @param start      startovní blok
     * @param goal       cílový blok
     * @param nodeBudget rozpočet uzlů (0 = z konfigurace)
     * @return future s cestou (nikdy neselže – při chybě vrací prázdnou cestu)
     */
    public CompletableFuture<Path> findPath(WorldView world, BlockPos start, BlockPos goal, int nodeBudget) {
        return request(world, start, goal, nodeBudget, List.of()).future();
    }

    /**
     * Naplánuje cestu s vyhýbáním se špatným vzpomínkám bota.
     *
     * @param world      pohled na svět
     * @param start      startovní blok
     * @param goal       cílový blok
     * @param nodeBudget rozpočet uzlů (0 = z konfigurace)
     * @param dangers    místa smrtí/nebezpečí z paměti bota (může být prázdné)
     * @return future s cestou (nikdy neselže – při chybě vrací prázdnou cestu)
     */
    public CompletableFuture<Path> findPath(WorldView world, BlockPos start, BlockPos goal,
                                            int nodeBudget, List<BlockPos> dangers) {
        return request(world, start, goal, nodeBudget, dangers).future();
    }

    /**
     * Naplánuje cestu asynchronně a vrátí zrušitelný handle.
     *
     * @param world      pohled na svět
     * @param start      startovní blok
     * @param goal       cílový blok
     * @param nodeBudget rozpočet uzlů (0 = z konfigurace)
     * @param dangers    místa smrtí/nebezpečí z paměti bota (může být prázdné)
     * @return handle výpočtu (future nikdy neselže – při chybě nese prázdnou cestu)
     */
    public PathRequest request(WorldView world, BlockPos start, BlockPos goal,
                               int nodeBudget, List<BlockPos> dangers) {
        return request(world, start, PathGoal.block(goal), nodeBudget, dangers);
    }

    /**
     * Naplánuje cestu k obecnému cíli ({@link PathGoal}) asynchronně.
     *
     * @param world      pohled na svět
     * @param start      startovní blok
     * @param goal       cílový predikát (okruh, útěk, hladina, kandidáti…)
     * @param nodeBudget rozpočet uzlů (0 = z konfigurace)
     * @param dangers    místa smrtí/nebezpečí z paměti bota (může být prázdné)
     * @return handle výpočtu (future nikdy neselže – při chybě nese prázdnou cestu)
     */
    public PathRequest request(WorldView world, BlockPos start, PathGoal goal,
                               int nodeBudget, List<BlockPos> dangers) {
        return request(world, start, goal, nodeBudget, dangers, PathCosts.DEFAULT);
    }

    /**
     * Naplánuje cestu s osobnostním profilem cen (styl cesty podle povahy).
     *
     * @param world      pohled na svět
     * @param start      startovní blok
     * @param goal       cílový predikát
     * @param nodeBudget rozpočet uzlů (0 = z konfigurace)
     * @param dangers    místa smrtí/nebezpečí z paměti bota (může být prázdné)
     * @param costs      profil cen ({@link PathCosts#of}); {@code null} = neutrální
     * @return handle výpočtu (future nikdy neselže – při chybě nese prázdnou cestu)
     */
    public PathRequest request(WorldView world, BlockPos start, PathGoal goal,
                               int nodeBudget, List<BlockPos> dangers, PathCosts costs) {
        return request(world, start, goal, nodeBudget, dangers, costs, PathOptions.WALK_ONLY);
    }

    /**
     * Naplánuje cestu s volbami výpočtu (kopací hrany – viz {@link PathOptions}).
     *
     * @param world      pohled na svět
     * @param start      startovní blok
     * @param goal       cílový predikát
     * @param nodeBudget rozpočet uzlů (0 = z konfigurace)
     * @param dangers    místa smrtí/nebezpečí z paměti bota (může být prázdné)
     * @param costs      profil cen ({@link PathCosts#of}); {@code null} = neutrální
     * @param options    volby výpočtu; {@code null} = čistě pěší
     * @return handle výpočtu (future nikdy neselže – při chybě nese prázdnou cestu)
     */
    public PathRequest request(WorldView world, BlockPos start, PathGoal goal,
                               int nodeBudget, List<BlockPos> dangers, PathCosts costs,
                               PathOptions options) {
        // Prefetch okolí, ať má A* s čím pracovat, než se pustí do výpočtu.
        world.prefetch(start, 2);
        world.prefetch(goal.anchor(), 1);
        int budget = nodeBudget > 0 ? nodeBudget : this.nodeBudget;
        AtomicBoolean cancelFlag = new AtomicBoolean();
        CompletableFuture<Path> future = CompletableFuture.supplyAsync(() -> {
            if (cancelFlag.get()) {
                return new Path(List.of(), false); // zrušeno ještě ve frontě poolu
            }
            try {
                AStarPathfinder.Result result = new AStarPathfinder(world, dangers, costs)
                        .findPath(start, goal, budget, timeBudgetMs, cancelFlag::get, options);
                stats.record(result);
                return result.path();
            } catch (Throwable t) {
                LOG.warn("Pathfinding selhal ({} -> {}): {}", start, goal.anchor(), t.toString());
                return new Path(List.of(), false);
            }
        }, executor);
        return new PathRequest(future, cancelFlag);
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
