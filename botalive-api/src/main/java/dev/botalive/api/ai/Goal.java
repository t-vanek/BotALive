package dev.botalive.api.ai;

import dev.botalive.api.bot.Bot;

/**
 * Jeden AI cíl bota (utility-based AI).
 *
 * <p>Mozek bota každý rozhodovací tick spočítá {@link #utility(Bot)} všech cílů
 * a vybere ten s nejvyšší hodnotou (s hysterezí, aby chování nekmitalo). Cíl pak
 * dostává {@link #tick(Bot)} každý herní tick, dokud neskončí nebo ho nepřebije
 * silnější cíl.</p>
 *
 * <p>Implementace nesmí blokovat – dlouhé výpočty (pathfinding) se delegují na
 * asynchronní služby a v ticku se pouze vyzvedávají výsledky.</p>
 */
public interface Goal {

    /**
     * @return stabilní identifikátor cíle (např. {@code "explore"}); používá se
     *         v příkazech, logách a persistenci
     */
    String id();

    /**
     * Spočítá užitečnost cíle v aktuální situaci.
     *
     * @param bot bot, pro kterého se cíl vyhodnocuje
     * @return užitečnost; {@code <= 0} znamená „teď nedává smysl“. Typický rozsah 0–100,
     *         přežití může vracet i více (přebije vše ostatní)
     */
    double utility(Bot bot);

    /**
     * Zavolá se jednou při aktivaci cíle.
     *
     * @param bot aktivující bot
     */
    void start(Bot bot);

    /**
     * Zavolá se každý herní tick, dokud je cíl aktivní.
     *
     * @param bot bot vykonávající cíl
     */
    void tick(Bot bot);

    /**
     * Zavolá se při deaktivaci cíle (dokončení i přerušení). Implementace musí
     * uklidit rozpracovaný stav (zrušit navigaci, pustit tlačítka, ...).
     *
     * @param bot deaktivující bot
     */
    void stop(Bot bot);

    /**
     * @param bot bot vykonávající cíl
     * @return {@code true} pokud cíl svou práci dokončil a má být vystřídán
     */
    boolean finished(Bot bot);

    /**
     * Blokuje běžící cíl rozhodnutí o stěhování (změně vesnice a domova)?
     * Například rozestavěný dům se nejdřív dostaví – parcela se nesmí
     * uvolnit pod rukama. Výchozí {@code false}.
     *
     * @return {@code true} pokud se má sousedská úvaha odložit
     */
    default boolean blocksRelocation() {
        return false;
    }
}
