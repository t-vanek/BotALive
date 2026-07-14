package dev.botalive.core.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Most mezi vlákny botů a vlákny serveru (main thread / region thread).
 *
 * <p>Používá výhradně Paper/Folia scheduler API (global/region/entity scheduler),
 * takže plugin funguje beze změny na Paperu i na Folii. AI botů běží mimo herní
 * vlákna; kdykoli potřebuje sáhnout na Bukkit svět (chunk snapshoty, inventáře),
 * jde přes tento most.</p>
 */
public final class MainThreadBridge {

    private final Plugin plugin;

    /**
     * @param plugin instance pluginu (vlastník naplánovaných úloh)
     */
    public MainThreadBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Spustí akci na globálním regionu (ekvivalent hlavního vlákna na Paperu).
     *
     * @param action akce
     */
    public void runGlobal(Runnable action) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, action);
    }

    /**
     * Spustí akci na vlákně vlastnícím region dané lokace (na Paperu hlavní vlákno).
     *
     * @param location lokace určující region
     * @param action   akce
     */
    public void runAt(Location location, Runnable action) {
        plugin.getServer().getRegionScheduler().execute(plugin, location, action);
    }

    /**
     * Spustí akci na vlákně vlastnícím entitu; pokud entita mezitím zmizela,
     * spustí se {@code retired} (může být {@code null}).
     *
     * @param entity  cílová entita
     * @param action  akce
     * @param retired fallback při odebrané entitě
     */
    public void runForEntity(Entity entity, Runnable action, Runnable retired) {
        entity.getScheduler().execute(plugin, action, retired, 1L);
    }

    /**
     * Vyhodnotí dotaz na regionu dané lokace a výsledek vrátí jako future.
     *
     * @param location lokace určující region
     * @param query    dotaz (běží na vlákně regionu)
     * @param <T>      typ výsledku
     * @return future s výsledkem dotazu (selže výjimkou dotazu)
     */
    public <T> CompletableFuture<T> callAt(Location location, Supplier<T> query) {
        CompletableFuture<T> future = new CompletableFuture<>();
        runAt(location, () -> {
            try {
                future.complete(query.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Vyhodnotí dotaz na vlákně entity a výsledek vrátí jako future.
     *
     * @param entity cílová entita
     * @param query  dotaz (běží na vlákně entity)
     * @param <T>    typ výsledku
     * @return future; dokončí se {@code null}, pokud byla entita odebrána
     */
    public <T> CompletableFuture<T> callForEntity(Entity entity, Supplier<T> query) {
        CompletableFuture<T> future = new CompletableFuture<>();
        runForEntity(entity,
                () -> {
                    try {
                        future.complete(query.get());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                },
                () -> future.complete(null));
        return future;
    }
}
