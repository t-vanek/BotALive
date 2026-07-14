package dev.botalive.core.chat;

import dev.botalive.core.util.BotRandom;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Banka frází pro přirozenou konverzaci botů – jedna instance na jazyk.
 *
 * <p>Fráze jsou záměrně krátké a hovorové; každý bot si z kategorie vybírá
 * náhodně (per-bot RNG), takže se odpovědi neopakují synchronně. Obsah se
 * načítá z jazykových souborů {@code lang/<kód>.yml}
 * (viz {@link PhraseBankLoader}) – kromě frází i <b>rozpoznávací vzory</b>
 * (pozdrav, poděkování), protože klasifikace příchozích zpráv je stejně
 * jazyková jako odpovědi.</p>
 *
 * <p>Instance je nemutabilní a sdílená všemi boty (čtou ji souběžně
 * z tick vláken). Fráze podporují placeholder {@code {name}} pro jméno
 * protistrany.</p>
 */
public final class PhraseBank {

    /** Flagy vzorů: case-insensitive a plný Unicode, aby {@code \b} fungovalo i na diakritiku. */
    static final int PATTERN_FLAGS = Pattern.CASE_INSENSITIVE
            | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private final Map<PhraseCategory, List<String>> phrases;
    private final Pattern greeting;
    private final Pattern thanks;

    /**
     * @param phrases  fráze podle kategorií (všechny kategorie, neprázdné seznamy)
     * @param greeting vzor rozpoznání pozdravu v příchozí zprávě
     * @param thanks   vzor rozpoznání poděkování v příchozí zprávě
     */
    PhraseBank(Map<PhraseCategory, List<String>> phrases, Pattern greeting, Pattern thanks) {
        EnumMap<PhraseCategory, List<String>> copy = new EnumMap<>(PhraseCategory.class);
        for (PhraseCategory category : PhraseCategory.values()) {
            List<String> list = phrases.get(category);
            if (list == null || list.isEmpty()) {
                throw new IllegalStateException("Chybí fráze kategorie " + category.key());
            }
            copy.put(category, List.copyOf(list));
        }
        this.phrases = copy;
        this.greeting = greeting;
        this.thanks = thanks;
    }

    /**
     * @param category kategorie
     * @return fráze kategorie (nemutabilní, nikdy prázdné)
     */
    public List<String> list(PhraseCategory category) {
        return phrases.get(category);
    }

    /**
     * Vybere frázi a doplní jméno.
     *
     * @param category kategorie frází
     * @param rng      per-bot náhoda
     * @param name     jméno protistrany (může být {@code null})
     * @return fráze
     */
    public String pick(PhraseCategory category, BotRandom rng, String name) {
        return rng.pick(list(category)).replace("{name}", name == null ? "" : name).trim();
    }

    /**
     * @param message příchozí zpráva (v původní velikosti písmen)
     * @return {@code true} pokud zpráva vypadá jako pozdrav
     */
    public boolean isGreeting(String message) {
        return greeting.matcher(message).find();
    }

    /**
     * @param message příchozí zpráva
     * @return {@code true} pokud zpráva vypadá jako poděkování
     */
    public boolean isThanks(String message) {
        return thanks.matcher(message).find();
    }

    /** @return vzor pozdravu (pro overlay při načítání) */
    Pattern greeting() {
        return greeting;
    }

    /** @return vzor poděkování (pro overlay při načítání) */
    Pattern thanks() {
        return thanks;
    }
}
