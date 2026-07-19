package dev.botalive.core.pathfinding;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Agregované metriky pathfindingu – sdílené všemi boty, thread-safe.
 *
 * <p>Bez čísel se nová verze pathfindingu nedá ladit ani obhájit: metriky
 * říkají, kolik výpočtů běží, jak dlouho trvají, kolik expandují a jak často
 * končí částečnou cestou, timeoutem či zrušením. Čtou se přes
 * {@code /botalive path}.</p>
 */
public final class PathfindingStats {

    private final LongAdder requests = new LongAdder();
    private final LongAdder complete = new LongAdder();
    private final LongAdder partial = new LongAdder();
    private final LongAdder empty = new LongAdder();
    private final LongAdder timedOut = new LongAdder();
    private final LongAdder cancelled = new LongAdder();
    private final LongAdder totalNodes = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final LongAccumulator maxNanos = new LongAccumulator(Long::max, 0);
    private final LongAccumulator maxNodes = new LongAccumulator(Long::max, 0);

    /** Zaznamená dokončený výpočet. */
    void record(AStarPathfinder.Result result) {
        requests.increment();
        totalNodes.add(result.expandedNodes());
        totalNanos.add(result.elapsedNanos());
        maxNodes.accumulate(result.expandedNodes());
        maxNanos.accumulate(result.elapsedNanos());
        if (result.timedOut()) {
            timedOut.increment();
        }
        if (result.cancelled()) {
            cancelled.increment();
        }
        Path path = result.path();
        if (path.complete()) {
            complete.increment();
        } else if (path.isEmpty()) {
            empty.increment();
        } else {
            partial.increment();
        }
    }

    /** @return konzistentní snímek metrik (přibližný – čítače běží dál) */
    public Snapshot snapshot() {
        long count = requests.sum();
        long nodes = totalNodes.sum();
        long nanos = totalNanos.sum();
        return new Snapshot(count, complete.sum(), partial.sum(), empty.sum(),
                timedOut.sum(), cancelled.sum(),
                count > 0 ? (double) nodes / count : 0,
                count > 0 ? nanos / count / 1_000_000.0 : 0,
                maxNanos.get() / 1_000_000.0,
                maxNodes.get());
    }

    /**
     * Snímek metrik.
     *
     * @param requests  celkem výpočtů
     * @param complete  cest dovedených až k cíli
     * @param partial   částečných cest (rozpočet/čas/zrušení)
     * @param empty     prázdných výsledků (nepochozí start, chyba)
     * @param timedOut  výpočtů ukončených časovým stropem
     * @param cancelled výpočtů ukončených zrušením
     * @param avgNodes  průměr expandovaných uzlů na výpočet
     * @param avgMillis průměrná doba výpočtu (ms)
     * @param maxMillis nejdelší výpočet (ms)
     * @param maxNodes  nejvíc expandovaných uzlů v jednom výpočtu
     */
    public record Snapshot(long requests, long complete, long partial, long empty,
                           long timedOut, long cancelled, double avgNodes,
                           double avgMillis, double maxMillis, long maxNodes) {
    }
}
