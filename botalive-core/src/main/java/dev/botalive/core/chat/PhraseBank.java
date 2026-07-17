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
 * (pozdrav, poděkování, otázka „co děláš?"), protože klasifikace příchozích
 * zpráv je stejně jazyková jako odpovědi.</p>
 *
 * <p>Instance je nemutabilní a sdílená všemi boty (čtou ji souběžně
 * z tick vláken). Fráze podporují placeholder {@code {name}} pro jméno
 * protistrany.</p>
 */
public final class PhraseBank {

    /** Flagy vzorů: case-insensitive a plný Unicode, aby {@code \b} fungovalo i na diakritiku. */
    static final int PATTERN_FLAGS = Pattern.CASE_INSENSITIVE
            | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    /** Klíče rozpoznávacích vzorů (sekce {@code patterns} jazykového souboru). */
    static final List<String> PATTERN_KEYS = List.of(
            "greeting", "thanks", "what-doing", "where-are-you", "what-have",
            "where-village", "come-here", "give-food");

    private final Map<PhraseCategory, List<String>> phrases;
    private final Map<String, Pattern> patterns;

    /**
     * @param phrases  fráze podle kategorií (všechny kategorie, neprázdné seznamy)
     * @param patterns rozpoznávací vzory podle klíčů {@link #PATTERN_KEYS}
     */
    PhraseBank(Map<PhraseCategory, List<String>> phrases, Map<String, Pattern> patterns) {
        EnumMap<PhraseCategory, List<String>> copy = new EnumMap<>(PhraseCategory.class);
        for (PhraseCategory category : PhraseCategory.values()) {
            List<String> list = phrases.get(category);
            if (list == null || list.isEmpty()) {
                throw new IllegalStateException("Chybí fráze kategorie " + category.key());
            }
            copy.put(category, List.copyOf(list));
        }
        this.phrases = copy;
        for (String key : PATTERN_KEYS) {
            if (patterns.get(key) == null) {
                throw new IllegalStateException("Chybí vzor patterns." + key);
            }
        }
        this.patterns = Map.copyOf(patterns);
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
     * @param key     klíč vzoru z {@link #PATTERN_KEYS}
     * @param message příchozí zpráva (v původní velikosti písmen)
     * @return {@code true} pokud zpráva vzoru odpovídá
     */
    public boolean matches(String key, String message) {
        Pattern pattern = patterns.get(key);
        return pattern != null && pattern.matcher(message).find();
    }

    /**
     * @param message příchozí zpráva (v původní velikosti písmen)
     * @return {@code true} pokud zpráva vypadá jako pozdrav
     */
    public boolean isGreeting(String message) {
        return matches("greeting", message);
    }

    /**
     * @param message příchozí zpráva
     * @return {@code true} pokud zpráva vypadá jako poděkování
     */
    public boolean isThanks(String message) {
        return matches("thanks", message);
    }

    /**
     * @param message příchozí zpráva
     * @return {@code true} pokud se zpráva ptá, co bot dělá
     */
    public boolean isAskingActivity(String message) {
        return matches("what-doing", message);
    }

    /** @return vzor daného klíče (pro overlay při načítání) */
    Pattern pattern(String key) {
        return patterns.get(key);
    }
}
