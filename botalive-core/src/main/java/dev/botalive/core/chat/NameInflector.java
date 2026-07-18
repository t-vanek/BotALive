package dev.botalive.core.chat;

import java.util.Locale;

/**
 * Skloňování jmen ve frázích – jazykově závislá strategie.
 *
 * <p>Fráze mohou žádat pád placeholderem {@code {name:2}}–{@code {name:7}}
 * (české školní číslování pádů). Jazyky bez pádů (angličtina) používají
 * {@link #IDENTITY} – placeholder se vyplní jménem beze změny, takže tentýž
 * mechanismus funguje pro všechny jazykové soubory.</p>
 */
@FunctionalInterface
public interface NameInflector {

    /** Jazyk bez skloňování – jméno se vrací beze změny. */
    NameInflector IDENTITY = (name, grammaticalCase) -> name;

    /**
     * @param name jméno/nick protistrany
     * @param grammaticalCase pád 1–7
     * @return tvar jména v daném pádu (při nejistotě beze změny)
     */
    String inflect(String name, int grammaticalCase);

    /**
     * @param languageCode kód jazyka frází (např. {@code cs})
     * @return skloňovač pro daný jazyk ({@link #IDENTITY}, když jazyk pády nemá)
     */
    static NameInflector forLanguage(String languageCode) {
        String code = languageCode == null ? "" : languageCode.toLowerCase(Locale.ROOT);
        return "cs".equals(code) ? CzechNames::decline : IDENTITY;
    }
}
