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
     * Zavolá se při deaktivaci cíle (dokončení, selhání nebo úplné opuštění).
     * Implementace musí uklidit rozpracovaný stav (zrušit navigaci, pustit
     * tlačítka, ...). Přerušení reflexem <em>s návratem</em> jde místo toho přes
     * {@link #pause}, takže {@code stop()} znamená „konec téhle práce".
     *
     * @param bot deaktivující bot
     */
    void stop(Bot bot);

    /**
     * Pozastaví cíl přerušený reflexem (na rozdíl od {@link #stop} = úplný
     * úklid). Výchozí chování je {@code stop(bot)} – cíl se zahodí jako dřív, což
     * je vždy bezpečné. Cíle s drahým rozdělaným stavem (rozkopané schodiště,
     * rozestavěná seance) můžou přepsat tak, aby stav zachovaly; k návratu se
     * pak zavolá {@link #resume}. Mozek {@code pause()} volá jen pro rozdělanou
     * práci přebitou reflexem (hlad, boj, útěk), ne pro dokončení ani dobrovolné
     * přepnutí na jinou práci.
     *
     * @param bot pozastavovaný bot
     */
    default void pause(Bot bot) {
        stop(bot);
    }

    /**
     * Naváže na cíl dřív pozastavený přes {@link #pause}. Výchozí chování je
     * {@code start(bot)} – svěží začátek jako dřív. Cíle přepisující
     * {@code pause()} přepíšou i tohle, aby pokračovaly tam, kde skončily
     * (nebo spadnou zpět na {@code start()}, když už rozdělaný stav neplatí).
     *
     * @param bot navracející se bot
     */
    default void resume(Bot bot) {
        start(bot);
    }

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
