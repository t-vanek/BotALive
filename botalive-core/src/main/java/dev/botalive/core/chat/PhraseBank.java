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
 * protistrany; {@code {name:2}}–{@code {name:7}} vyplní jméno ve skloněném
 * pádu (české číslování, {@code {name:5}} = oslovení), pokud jazyk frází
 * skloňování má ({@link NameInflector}).</p>
 */
public final class PhraseBank {

    /** Flagy vzorů: case-insensitive a plný Unicode, aby {@code \b} fungovalo i na diakritiku. */
    static final int PATTERN_FLAGS = Pattern.CASE_INSENSITIVE
            | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    /** Klíče rozpoznávacích vzorů (sekce {@code patterns} jazykového souboru). */
    static final List<String> PATTERN_KEYS = List.of(
            "greeting", "thanks", "what-doing", "where-are-you", "what-have",
            "where-village", "come-here", "give-food", "help", "give-item");

    /** Placeholder jména s volitelným pádem: {@code {name}} nebo {@code {name:5}}. */
    private static final Pattern NAME_PLACEHOLDER = Pattern.compile("\\{name(?::([1-7]))?\\}");

    private final Map<PhraseCategory, List<String>> phrases;
    private final Map<String, Pattern> patterns;
    /** Aliasy itemů pro prosby: slovo (malými, jak je v yml) → materiály. */
    private final Map<String, List<org.bukkit.Material>> itemAliases;
    private final NameInflector inflector;

    /**
     * @param phrases     fráze podle kategorií (všechny kategorie, neprázdné seznamy)
     * @param patterns    rozpoznávací vzory podle klíčů {@link #PATTERN_KEYS}
     * @param itemAliases aliasy itemů pro prosby (může být prázdné)
     * @param inflector   skloňování jmen podle jazyka frází
     */
    PhraseBank(Map<PhraseCategory, List<String>> phrases, Map<String, Pattern> patterns,
               Map<String, List<org.bukkit.Material>> itemAliases, NameInflector inflector) {
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
        this.itemAliases = Map.copyOf(itemAliases);
        this.inflector = inflector == null ? NameInflector.IDENTITY : inflector;
    }

    /**
     * @param newInflector skloňovač cílového jazyka
     * @return kopie banky se zadaným skloňovačem (fráze a vzory sdílené)
     */
    PhraseBank withInflector(NameInflector newInflector) {
        return new PhraseBank(phrases, patterns, itemAliases, newInflector);
    }

    /**
     * Najde ve zprávě žádané itemy podle aliasů jazyka.
     *
     * @param message příchozí zpráva
     * @return materiály, o které si pisatel říká (bez duplicit, může být prázdné)
     */
    public List<org.bukkit.Material> requestedItems(String message) {
        if (itemAliases.isEmpty()) {
            return List.of();
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        java.util.LinkedHashSet<org.bukkit.Material> found = new java.util.LinkedHashSet<>();
        for (String word : lower.split("[^\\p{L}\\p{N}]+")) {
            List<org.bukkit.Material> materials = itemAliases.get(word);
            if (materials != null) {
                found.addAll(materials);
            }
        }
        return List.copyOf(found);
    }

    /**
     * Tvary jména bota, na které má reagovat jako na zmínku – včetně
     * skloněných pádů a jádra nicku („Karle", „Ninjo"), malými písmeny.
     *
     * @param botName jméno bota
     * @return množina tvarů (vždy obsahuje aspoň jméno samotné)
     */
    public java.util.Set<String> mentionForms(String botName) {
        java.util.Set<String> forms = new java.util.HashSet<>();
        forms.add(botName.toLowerCase(java.util.Locale.ROOT));
        for (int grammaticalCase = 2; grammaticalCase <= 7; grammaticalCase++) {
            String declined = inflector.inflect(botName, grammaticalCase);
            if (declined != null && !declined.isEmpty()) {
                forms.add(declined.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return forms;
    }

    /**
     * @param category kategorie
     * @return fráze kategorie (nemutabilní, nikdy prázdné)
     */
    public List<String> list(PhraseCategory category) {
        return phrases.get(category);
    }

    /**
     * Vybere frázi a doplní jméno (ve skloněném pádu, žádá-li ho placeholder).
     *
     * @param category kategorie frází
     * @param rng      per-bot náhoda
     * @param name     jméno protistrany (může být {@code null})
     * @return fráze
     */
    public String pick(PhraseCategory category, BotRandom rng, String name) {
        String phrase = rng.pick(list(category));
        java.util.regex.Matcher m = NAME_PLACEHOLDER.matcher(phrase);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = "";
            if (name != null && !name.isEmpty()) {
                int grammaticalCase = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
                value = grammaticalCase == 1 ? name : inflector.inflect(name, grammaticalCase);
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString().trim();
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

    /** @return aliasy itemů (pro overlay při načítání) */
    Map<String, List<org.bukkit.Material>> itemAliases() {
        return itemAliases;
    }
}
