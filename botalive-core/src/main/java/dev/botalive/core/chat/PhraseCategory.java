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
    /** Reakce na přepadení hráčem (vztek/strach podle povahy). */
    ATTACKED,
    /** Reakce na zranění mobem (zombie, kostlivec…). */
    HURT_BY_MOB,
    /** Varování před nebezpečným mobem poblíž (creeper!). */
    MOB_WARNING,
    /** Komentář, když začne pršet. */
    WEATHER_RAIN,
    /** Komentář, když začne bouřka. */
    WEATHER_THUNDER,
    /** Komentář při soumraku (jde tma, mobové). */
    NIGHTFALL,
    /** Stěžování na hlad (málo jídla v žaludku). */
    HUNGRY,
    /** Stěžování na málo životů. */
    LOW_HEALTH,
    /** Souhlas s prosbou (pojď za mnou, pomoc…). */
    REQUEST_ACCEPT,
    /** Odmítnutí prosby. */
    REQUEST_DECLINE,
    /** Předání itemu/jídla („na, chytej"). */
    GIVE_ACCEPT,
    /** Odmítnutí předání (nemám / nechám si). */
    GIVE_DECLINE,
    /** Založení vesnice ({name} = jméno vesnice). */
    SETTLEMENT_FOUNDED,
    /** Vstup do vesnice ({name} = jméno vesnice). */
    SETTLEMENT_JOINED,
    /** Roztržka – odchod z vesnice kvůli nepříteli ({name} = nepřítel). */
    SETTLEMENT_SPLINTER,
    /** Stěhování za kamarádem do jiné vesnice ({name} = kamarád). */
    SETTLEMENT_FOLLOW,
    /** Běh pro věci po smrti – vyrážím. */
    RECOVER_RUN,
    /** Věci po smrti zachráněné. */
    RECOVER_OK,
    /** Věci po smrti v háji (pozdě / nedostupné). */
    RECOVER_FAIL,
    /** Oprava poškozeného domu (bručení nad dírami). */
    HOME_REPAIR,
    /** Nová životní ambice po splnění staré ({name} = popis cíle). */
    AMBITION_NEW,
    /** Vyvolávání nabídky na trhu ({name} = zboží a cena). */
    MARKET_OFFER,
    /** Plácnutí obchodu ({name} = protistrana). */
    MARKET_DEAL,
    /** Zrušení obchodu – kupec nemá peníze ({name} = kupec). */
    MARKET_DECLINE,
    /** Odpověď „o žádné vesnici nevím" (na dotaz, když bot žádnou nepamatuje). */
    NO_VILLAGE_KNOWN,
    /** Výzva hráči k platbě přes /pay ({name} = cena). */
    MARKET_PAY_REQUEST,
    /** Komentář po výměně drbů při socializaci ({name} = protistrana). */
    GOSSIP,
    /** Přivítání hráče ve vesnici ({name} = jméno vesnice). */
    VILLAGE_WELCOME,
    /** Nabídka kamarádovi-hráči provést ho vesnicí ({name} = hráč). */
    VILLAGE_TOUR,
    /** Zloděj nese oběti dar na usmířenou ({name} = oběť). */
    RECONCILE_OFFER,
    /** Oběť dar přijímá – zášť opadá ({name} = zloděj). */
    RECONCILE_ACCEPT,
    /** Oběť dar odmítá ({name} = zloděj). */
    RECONCILE_REJECT,
    /** Lovec nastupuje na noční hlídku vesnice. */
    GUARD_NIGHT,
    /** Odchod na výpravu do Endu (u portálu / před cestou). */
    END_DEPART,
    /** Příchod do Endu (první dojmy po průchodu portálem). */
    END_ARRIVE,
    /** Oslava skolení ender draka. */
    DRAGON_SLAIN,
    /** Návrat z Endu domů (hledání výstupního portálu). */
    END_RETURN,
    /** Smajlíky přidávané podle stylu psaní. */
    EMOJIS;

    /** @return klíč kategorie v YAML (kebab-case, např. {@code youre-welcome}) */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
