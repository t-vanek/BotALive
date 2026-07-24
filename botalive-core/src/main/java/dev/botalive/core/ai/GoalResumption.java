package dev.botalive.core.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Krátkodobá paměť „rozdělané práce" – doplněk hystereze pro <b>návrat po
 * přerušení</b>. Hystereze v mozku zvýhodňuje jen PRÁVĚ aktivní cíl; jakmile
 * ho ale přebije reflex (hlad, boj, útěk, dodělání úkrytu), přerušený cíl o ni
 * přijde a po odeznění hrozby soutěží „od nuly" – bot pak místo návratu k rudě
 * či rozestavěnému domu klidně odejde farmařit. Tahle třída drží pro nedávno
 * přerušený cíl malou klesavou pobídku, tedy „přenosnou hysterezi", která
 * přežije přerušení a vrátí bota k tomu, co dělal.
 *
 * <p>Pobídku dostane <b>jen skutečné přerušení</b>: rozdělaná produktivní práce
 * nebo výprava ({@link #RESUMABLE}) přebitá reflexem. Dobrovolné přepnutí mezi
 * dvěma produktivními cíli (změna priorit rytmem, náladou…) přerušení není –
 * jinak by pobídka rozhoupala rozhodování tam a zpět. Reflexy (jídlo, spánek,
 * boj, útěk, návrat domů…) se samy spouštějí z podmínky, takže se „po přerušení
 * dokončovat" nemají a pobídku nedostávají nikdy.</p>
 *
 * <p>Pobídka klesá každý rozhodovací krok (dávno opuštěná práce ztrácí nárok –
 * svět se mezitím pohnul), zmizí pod prahem, a maže se, jakmile se bot k cíli
 * vrátí (pak převezme hystereze). Schválně se <b>neruší</b>, když cíl chvíli
 * čte utilitu 0 – přesně to se děje během reflexu, který má pobídka přežít
 * (výprava přebitá bojem má health/food pod prahem výpravy). Pobídka je
 * multiplikátor, takže cíl s utilitou 0 stejně nevyhraje; jakmile je zas
 * proveditelný, vrátí k němu bota. Nic se nepersistuje: po odpojení má bot
 * čistý stůl, ne posedlost dávným úkolem.</p>
 *
 * <p>Čistá třída bez závislostí; přístup z jednoho vlákna (rozhodování mozku),
 * metody jsou {@code synchronized} pro jistotu jako u {@link GoalMomentum}.</p>
 */
public final class GoalResumption {

    /** Plná pobídka po čerstvém přerušení (odtud klesá). */
    private static final double INITIAL = 1.0;
    /** Slábnutí za jeden rozhodovací krok (poločas ~34 s při intervalu 5 ticků). */
    private static final double DECAY = 0.995;
    /**
     * Maximální bonus utility z plné pobídky. Drží se nad hysterezí (0.15),
     * aby vrátil bota i proti mírné převaze čerstvého konkurenta, ale pod
     * frontier tahem ambice (0.6) – návrat vylaďuje, nepřebíjí záměr.
     */
    private static final double BONUS = 0.25;
    /** Pod touto pobídkou se záznam zahodí (úklid mapy). */
    private static final double FLOOR = 0.05;

    /**
     * Cíle, ke kterým se má smysl vracet: zvolená produktivní práce (shodná
     * s {@link GoalMomentum} – co bot dělá pro výsledek) plus dlouhé výpravy
     * a stavební projekty, tedy typické oběti přerušení bojem uprostřed cesty.
     * Reflexy, hlídání, družení, toulky ani zločin tu schválně nejsou.
     */
    private static final Set<String> RESUMABLE = Set.of(
            // produktivní práce (zrcadlí GoalMomentum.PRODUCTIVE)
            "mine", "house", "craft", "farm", "fish", "smelt", "sell", "trade",
            "communal-build", "hunt", "enchant", "smith", "brew", "stash", "shear",
            "nether", "compost",
            // dlouhé výpravy a stavební projekty
            "explore", "end-travel", "dragon-fight", "stronghold", "end-harvest",
            "settlement-walls", "settlement-fences", "settlement-roads");

    private final Map<String, Double> charge = new HashMap<>();

    /**
     * Smí tenhle cíl dostat návratovou pobídku? (rozdělaná práce ano, reflexy
     * a jednorázovky ne). Mozek se tím řídí i při rozhodnutí, jestli výměnu
     * vůbec brát jako přerušení.
     *
     * @param goalId id cíle
     * @return {@code true} pro produktivní práci a výpravy
     */
    public boolean resumable(String goalId) {
        return RESUMABLE.contains(goalId);
    }

    /**
     * Rozdělaná práce přerušená reflexem → dostane plnou pobídku k návratu
     * (cíle mimo {@link #RESUMABLE} ignoruje).
     *
     * @param goalId přerušený cíl
     */
    public synchronized void interrupted(String goalId) {
        if (!RESUMABLE.contains(goalId)) {
            return;
        }
        charge.put(goalId, INITIAL);
    }

    /**
     * Zruší pobídku cíle – volá se, když se k němu bot vrátil (dál ho drží
     * hystereze). Neproveditelnost pobídku nemaže; o dávno opuštěnou práci se
     * postará decay.
     *
     * @param goalId cíl
     */
    public synchronized void clear(String goalId) {
        charge.remove(goalId);
    }

    /** Jeden rozhodovací krok – veškeré pobídky o kus zeslábnou. */
    public synchronized void decay() {
        charge.replaceAll((id, value) -> value * DECAY);
        charge.values().removeIf(value -> value < FLOOR);
    }

    /**
     * Násobič utility z návratové pobídky.
     *
     * @param goalId cíl
     * @return {@code 1.0} bez pobídky, nejvýš {@code 1 + BONUS}
     */
    public synchronized double weight(String goalId) {
        return 1.0 + charge.getOrDefault(goalId, 0.0) * BONUS;
    }

    /**
     * Čeká tenhle cíl na návrat? (diagnostika a testy).
     *
     * @param goalId cíl
     * @return {@code true} pokud má nenulovou pobídku
     */
    public synchronized boolean pending(String goalId) {
        return charge.containsKey(goalId);
    }
}
