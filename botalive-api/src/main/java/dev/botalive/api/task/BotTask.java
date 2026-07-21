package dev.botalive.api.task;

import dev.botalive.api.bot.BotControl;

/**
 * Krátkodobá, dokončitelná akce bota – taktická vrstva, ze které cizí AI cíle
 * skládají chování (dojdi tam, vytěž blok, polož blok).
 *
 * <p>Task je stavový automat: cizí {@link dev.botalive.api.ai.Goal} si ho drží
 * a každý tick volá {@link #tick(BotControl)}, dokud task nevrátí {@code true}
 * (hotovo). Musí být zrušitelný v libovolné fázi ({@link #cancel(BotControl)}).</p>
 *
 * <p>Vestavěná primitiva vytvoří {@link BotControl} ({@code mineBlock},
 * {@code placeBlock}, {@code walkTo}); vlastní tasky lze registrovat přes
 * {@link TaskRegistry}. Volá se z tick vlákna bota.</p>
 */
public interface BotTask {

    /**
     * Jeden tick tasku.
     *
     * @param control řídicí rozhraní bota
     * @return {@code true} pokud task skončil (úspěchem i neúspěchem)
     */
    boolean tick(BotControl control);

    /**
     * Zrušení tasku – uklidí rozdělanou práci (přeruší kopání, zastaví
     * navigaci…). Výchozí implementace nedělá nic.
     *
     * @param control řídicí rozhraní bota
     */
    default void cancel(BotControl control) {
    }
}
