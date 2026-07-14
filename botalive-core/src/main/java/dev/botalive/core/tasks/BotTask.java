package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;

/**
 * Krátkodobá, dokončitelná akce bota (taktická vrstva pod cíli).
 *
 * <p>Cíle (strategie) skládají tasky (taktiku): „vytěž blok", „polož blok",
 * „sněz jídlo". Task je stavový automat tickovaný z aktivního cíle a musí
 * být zrušitelný v libovolné fázi.</p>
 */
public interface BotTask {

    /**
     * Jeden tick tasku.
     *
     * @param ctx kontext bota
     * @return {@code true} pokud task skončil (úspěchem i neúspěchem)
     */
    boolean tick(BotContext ctx);

    /**
     * Zrušení tasku – musí uklidit rozdělanou práci (např. cancel digging).
     *
     * @param ctx kontext bota
     */
    void cancel(BotContext ctx);
}
