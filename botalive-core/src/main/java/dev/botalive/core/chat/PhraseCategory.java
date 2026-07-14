package dev.botalive.core.chat;

import java.util.Locale;

/**
 * Kategorie frází v bance ({@link PhraseBank}).
 *
 * <p>Enum je kontrakt mezi kódem (cíle, chat engine) a jazykovými soubory
 * {@code lang/<kód>.yml}: každá kategorie odpovídá klíči v sekci
 * {@code phrases} (kebab-case, viz {@link #key()}). Přidání kategorie
 * v kódu bez doplnění do vestavěné češtiny zachytí unit test úplnosti.</p>
 */
public enum PhraseCategory {

    /** Pozdravy (odpověď na pozdrav). */
    GREETINGS,
    /** Odpovědi na otázku, kterou bot nechápe. */
    CONFUSED,
    /** Souhlas. */
    AGREEMENT,
    /** Nesouhlas. */
    DISAGREEMENT,
    /** Reakce na poděkování. */
    YOURE_WELCOME,
    /** Spontánní hlášky při nudě. */
    IDLE_CHATTER,
    /** Reakce na vlastní smrt (po respawnu). */
    DEATH_REACTIONS,
    /** Reakce na boj / vítězství (PvE). */
    COMBAT_TAUNTS,
    /** Pozdrav při potkání hráče. */
    MEET_PLAYER,
    /** Volání o pomoc při napadení. */
    PVP_HELP_CALLS,
    /** Reakce spojence, který jde na pomoc. */
    PVP_ASSIST,
    /** Hlášky po vyhraném souboji. */
    PVP_TAUNTS,
    /** Smajlíky přidávané podle stylu psaní. */
    EMOJIS;

    /** @return klíč kategorie v YAML (kebab-case, např. {@code youre-welcome}) */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
