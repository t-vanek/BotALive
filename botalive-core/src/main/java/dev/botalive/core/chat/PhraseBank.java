package dev.botalive.core.chat;

import dev.botalive.core.util.BotRandom;

import java.util.List;

/**
 * Banka frází pro přirozenou konverzaci botů.
 *
 * <p>Fráze jsou záměrně krátké a hovorové; každý bot si z kategorie vybírá
 * náhodně (per-bot RNG), takže se odpovědi neopakují synchronně. Obsahuje
 * placeholder {@code {name}} pro jméno protistrany.</p>
 */
public final class PhraseBank {

    private PhraseBank() {
    }

    /** Pozdravy. */
    public static final List<String> GREETINGS = List.of(
            "ahoj {name}", "čau", "nazdar {name}", "zdravím", "čauky", "ahojky",
            "hej {name}", "zdar", "dobrej", "čus {name}"
    );

    /** Odpovědi na otázku, kterou bot nechápe. */
    public static final List<String> CONFUSED = List.of(
            "co?", "jak to myslíš?", "nevím no", "hmm", "těžko říct", "možná",
            "to nevím", "asi jo?", "netuším {name}", "dobrá otázka"
    );

    /** Souhlas. */
    public static final List<String> AGREEMENT = List.of(
            "jo", "jasně", "souhlas", "přesně tak", "jj", "no jasný", "to jo", "určitě"
    );

    /** Nesouhlas. */
    public static final List<String> DISAGREEMENT = List.of(
            "ne", "to ne", "nemyslím si", "hmm to asi ne", "nn", "spíš ne"
    );

    /** Reakce na poděkování. */
    public static final List<String> YOURE_WELCOME = List.of(
            "nz", "není zač", "v pohodě", "za málo", "np", "kdykoli"
    );

    /** Spontánní hlášky při nudě. */
    public static final List<String> IDLE_CHATTER = List.of(
            "nuda", "jdu se projít", "někdo nechce jít těžit?", "hezkej den dneska",
            "kde je nějaká vesnice?", "potřebuju dřevo", "má někdo jídlo navíc?",
            "jdu kopat", "zase prší no"
    );

    /** Reakce na vlastní smrt (po respawnu). */
    public static final List<String> DEATH_REACTIONS = List.of(
            "no super", "zase jsem umřel", "kdo mě zabil?!", "ff", "moje věci!",
            "to snad ne", "au", "tak znova", "rip moje diamanty"
    );

    /** Reakce na boj / vítězství. */
    public static final List<String> COMBAT_TAUNTS = List.of(
            "a je po něm", "to bylo o fous", "hotovo", "ez", "další!", "uff to bolelo"
    );

    /** Pozdrav při potkání hráče. */
    public static final List<String> MEET_PLAYER = List.of(
            "čau {name}, co děláš?", "ahoj {name}", "hej {name}, nemáš jídlo?",
            "{name} ahoj, kde je nejbližší vesnice?", "čus {name}, jdeš těžit?"
    );

    /** Volání o pomoc při napadení. */
    public static final List<String> PVP_HELP_CALLS = List.of(
            "pomoc!", "jdou po mně!", "pomozte mi někdo", "au au au",
            "bijou mě!", "sem, rychle!"
    );

    /** Reakce spojence, který jde na pomoc. */
    public static final List<String> PVP_ASSIST = List.of(
            "držím tě", "jdu tam", "nech ho být!", "vydrž, jdu",
            "toho si podám", "za mnou!"
    );

    /** Hlášky po vyhraném souboji. */
    public static final List<String> PVP_TAUNTS = List.of(
            "máš dost?", "ez", "to bylo za minule", "kdo dál?",
            "a zůstaň ležet", "příště si rozmysli"
    );

    /** Smajlíky. */
    public static final List<String> EMOJIS = List.of(
            ":D", ":)", "xd", ":P", "😀", "😂", ":O", "o.O"
    );

    /**
     * Vybere frázi a doplní jméno.
     *
     * @param bank kategorie frází
     * @param rng  per-bot náhoda
     * @param name jméno protistrany (může být prázdné)
     * @return fráze
     */
    public static String pick(List<String> bank, BotRandom rng, String name) {
        return rng.pick(bank).replace("{name}", name == null ? "" : name).trim();
    }
}
