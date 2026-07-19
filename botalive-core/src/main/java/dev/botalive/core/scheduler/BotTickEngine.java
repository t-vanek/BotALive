package dev.botalive.core.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vícevláknový tick engine botů.
 *
 * <p>Každý bot dostane vlastní periodickou úlohu s frekvencí 20 Hz (50 ms) a
 * náhodným počátečním rozfázováním, aby se boti netickali synchronně a zátěž
 * byla rozprostřená. Tick jednoho bota běží vždy sekvenčně (perioda fixedRate
 * na jedné úloze), takže per-bot stav nepotřebuje zámky – je confinovaný na
 * tick vlákno bota.</p>
 *
 * <p>Výjimka v ticku bota nesmí zabít jeho úlohu ani vlákno – loguje se
 * a tick pokračuje dalším cyklem.</p>
 */
public final class BotTickEngine {

    private static final Logger LOG = LoggerFactory.getLogger(BotTickEngine.class);
    private static final long TICK_PERIOD_MS = 50L;
    /** Jak dlouho se opakování téže výjimky v ticku jen počítá místo logování. */
    private static final long ERROR_DEDUP_MS = 60_000L;

    private final ScheduledThreadPoolExecutor executor;
    private final Map<Object, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final Map<Object, TickErrorState> tickErrors = new ConcurrentHashMap<>();

    /** Deduplikace opakované výjimky v ticku jednoho bota (chráněno vlastním zámkem). */
    private static final class TickErrorState {
        String signature;
        long lastLogAt;
        int suppressed;
    }

    /**
     * @param configuredThreads počet vláken z konfigurace; 0 = auto (¼ CPU, min 2)
     */
    public BotTickEngine(int configuredThreads) {
        int threads = configuredThreads > 0
                ? configuredThreads
                : Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "BotAlive-Tick-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.executor = new ScheduledThreadPoolExecutor(threads, factory);
        this.executor.setRemoveOnCancelPolicy(true);
        LOG.info("Tick engine spuštěn s {} vlákny", threads);
    }

    /**
     * Zaregistruje periodický tick bota.
     *
     * @param owner     klíč vlastníka (instance bota)
     * @param tick      tělo ticku
     * @param staggerMs počáteční rozfázování 0–49 ms (dodá volající z per-bot náhody)
     */
    public void startTicking(Object owner, Runnable tick, long staggerMs) {
        stopTicking(owner);
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            try {
                tick.run();
            } catch (Throwable t) {
                logTickError(owner, t);
            }
        }, staggerMs, TICK_PERIOD_MS, TimeUnit.MILLISECONDS);
        tasks.put(owner, future);
    }

    /**
     * Zaloguje výjimku z ticku s deduplikací: tatáž výjimka (třída + zpráva)
     * u téhož bota se v okně {@link #ERROR_DEDUP_MS} jen počítá – zaseklý bot
     * s chybou každý tick by jinak zaplavil log stovkami identických stacků.
     */
    private void logTickError(Object owner, Throwable t) {
        String signature = t.getClass().getName() + ": " + t.getMessage();
        TickErrorState state = tickErrors.computeIfAbsent(owner, o -> new TickErrorState());
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (signature.equals(state.signature) && now - state.lastLogAt < ERROR_DEDUP_MS) {
                state.suppressed++;
                return;
            }
            int suppressed = state.suppressed;
            state.signature = signature;
            state.lastLogAt = now;
            state.suppressed = 0;
            if (suppressed > 0) {
                LOG.error("Neošetřená výjimka v ticku bota {} (a {}× potlačené opakování)",
                        owner, suppressed, t);
            } else {
                LOG.error("Neošetřená výjimka v ticku bota {}", owner, t);
            }
        }
    }

    /**
     * Odregistruje tick bota (bez přerušení právě běžícího ticku).
     *
     * @param owner klíč vlastníka
     */
    public void stopTicking(Object owner) {
        ScheduledFuture<?> future = tasks.remove(owner);
        tickErrors.remove(owner);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * Naplánuje jednorázovou úlohu (např. odložený reconnect).
     *
     * @param action  akce
     * @param delayMs zpoždění v ms
     * @return future pro případné zrušení
     */
    public ScheduledFuture<?> schedule(Runnable action, long delayMs) {
        return executor.schedule(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                LOG.error("Neošetřená výjimka v naplánované úloze", t);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Zastaví celý engine (při vypnutí pluginu).
     */
    public void shutdown() {
        tasks.values().forEach(f -> f.cancel(false));
        tasks.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
