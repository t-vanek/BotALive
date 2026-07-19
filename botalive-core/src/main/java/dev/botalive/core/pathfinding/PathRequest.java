package dev.botalive.core.pathfinding;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handle běžícího výpočtu cesty s možností kooperativního zrušení.
 *
 * <p>Zrušení není tvrdé přerušení vlákna: nastaví signál, který A* kontroluje
 * po blocích expanzí, a výpočet se ukončí při nejbližší kontrole (vrátí
 * dosavadní částečnou cestu, kterou už nikdo nečte). Bez zrušení by pool
 * mlel mrtvou práci pokaždé, když bot změní cíl dřív, než se cesta dopočítá
 * (boj, útěk, nový segment).</p>
 */
public final class PathRequest {

    private final CompletableFuture<Path> future;
    private final AtomicBoolean cancelled;

    PathRequest(CompletableFuture<Path> future, AtomicBoolean cancelled) {
        this.future = future;
        this.cancelled = cancelled;
    }

    /** @return {@code true} když je výpočet dokončený */
    public boolean isDone() {
        return future.isDone();
    }

    /** @return výsledná cesta (volat až po {@link #isDone()}) */
    public Path join() {
        return future.join();
    }

    /** @return future výsledku (pro kompozici a testy) */
    public CompletableFuture<Path> future() {
        return future;
    }

    /** Kooperativně zruší výpočet – běžící A* skončí při nejbližší kontrole. */
    public void cancel() {
        cancelled.set(true);
    }
}
