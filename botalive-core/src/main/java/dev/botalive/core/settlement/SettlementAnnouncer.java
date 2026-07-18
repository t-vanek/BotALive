package dev.botalive.core.settlement;

import dev.botalive.core.chat.ChatEngine;
import dev.botalive.core.chat.PhraseCategory;

/**
 * Jediné místo, které mapuje změny členství ve vesnici na chatové hlášky.
 *
 * <p>Členství se mění ze dvou vrstev (sousedská úvaha v {@code BotImpl},
 * stavební seance v {@code BuildHouseGoal}) – kdyby si každá volila fráze
 * sama, konvence by se rozjely. Tady je celý kontrakt: kategorie, kdo je
 * {@code {name}} a že roztržka/stěhování bez známého jména protistrany
 * mlčí (skloňovaná fráze s prázdným jménem je paskvil).</p>
 */
public final class SettlementAnnouncer {

    private SettlementAnnouncer() {
    }

    /**
     * Ohlásí výsledek sousedské úvahy.
     *
     * @param chat   chat bota
     * @param action provedená akce
     */
    public static void say(ChatEngine chat, SettlementService.CohesionAction action) {
        switch (action.type()) {
            case GRUDGE_LEAVE -> sayWithName(chat, PhraseCategory.SETTLEMENT_SPLINTER,
                    action.otherName());
            case FOLLOW_FRIEND -> sayWithName(chat, PhraseCategory.SETTLEMENT_FOLLOW,
                    action.otherName());
            case JOIN_NEARBY -> sayJoined(chat, action.settlementName());
            case FOUND_AT_HOME -> sayFounded(chat, action.settlementName());
        }
    }

    /**
     * Ohlásí vstup do vesnice (i z cesty za bydlením).
     *
     * @param chat           chat bota
     * @param settlementName jméno vesnice
     */
    public static void sayJoined(ChatEngine chat, String settlementName) {
        sayWithName(chat, PhraseCategory.SETTLEMENT_JOINED, settlementName);
    }

    /**
     * Ohlásí založení vesnice.
     *
     * @param chat           chat bota
     * @param settlementName jméno vesnice
     */
    public static void sayFounded(ChatEngine chat, String settlementName) {
        sayWithName(chat, PhraseCategory.SETTLEMENT_FOUNDED, settlementName);
    }

    private static void sayWithName(ChatEngine chat, PhraseCategory category, String name) {
        if (name != null) {
            chat.sayFrom(category, name);
        }
    }
}
