package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.container.ContainerView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Rámec asynchronních toků paketových stanic.
 *
 * <p>Každá operace (uložení do truhly, výroba, obchod…) běží na vlastním
 * <b>virtuálním vláknu</b>: tok je čitelný imperativní kód s {@code sleep}
 * pauzami (lidské tempo klikání 120–320 ms), zatímco odpovědi serveru
 * průběžně aktualizují {@link ContainerView} ze síťového vlákna. Blokování
 * virtuálního vlákna je zadarmo – OS vlákna se nedrží.</p>
 *
 * <p>Chyby toku se nikdy nepropagují: operace vrátí fallback a zaloguje se
 * warn – bot pokrčí rameny a jde dál (stejně jako server-side služby).</p>
 */
final class StationFlow {

    private static final Logger LOG = LoggerFactory.getLogger(StationFlow.class);

    /** Krok pollingu čekacích podmínek (ms). */
    private static final long POLL_MS = 25;

    private StationFlow() {
    }

    /**
     * Spustí tok stanice na virtuálním vláknu.
     *
     * @param name     název operace (pro log a jméno vlákna)
     * @param fallback výsledek při chybě/timeoutu
     * @param body     tělo toku (smí blokovat)
     * @param <T>      typ výsledku
     * @return future s výsledkem (nikdy nedokončená výjimkou)
     */
    static <T> CompletableFuture<T> run(String name, T fallback, Callable<T> body) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread.ofVirtual().name("botalive-station-" + name).start(() -> {
            try {
                T result = body.call();
                future.complete(result == null ? fallback : result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.complete(fallback);
            } catch (Exception e) {
                LOG.warn("Stanice {} selhala: {}", name, e.toString());
                future.complete(fallback);
            }
        });
        return future;
    }

    /**
     * Počká na splnění podmínky.
     *
     * @param condition podmínka
     * @param timeoutMs maximální čekání
     * @return {@code true} pokud se podmínka splnila včas
     */
    static boolean await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_MS);
        }
        return condition.getAsBoolean();
    }

    /**
     * Počká na otevřené okno s načteným obsahem splňující filtr. Okno musí
     * souhlasit s {@code openContainerId} klientského stavu (ochrana proti
     * zastaralému oknu po zavření).
     *
     * @param ctx       kontext bota
     * @param filter    dodatečná podmínka na okno (typ…)
     * @param timeoutMs maximální čekání
     * @return okno, nebo {@code null} při timeoutu
     */
    static ContainerView awaitWindow(BotContext ctx, Predicate<ContainerView> filter,
                                     long timeoutMs) throws InterruptedException {
        await(() -> {
            ContainerView view = ctx.containers().open();
            return view != null && view.contentLoaded()
                    && view.containerId() == ctx.clientState().openContainerId()
                    && filter.test(view);
        }, timeoutMs);
        ContainerView view = ctx.containers().open();
        return view != null && view.contentLoaded()
                && view.containerId() == ctx.clientState().openContainerId()
                && filter.test(view) ? view : null;
    }

    /** Lidská pauza mezi kliky (120–320 ms). */
    static void humanPause() throws InterruptedException {
        Thread.sleep(ThreadLocalRandom.current().nextLong(120, 320));
    }
}
