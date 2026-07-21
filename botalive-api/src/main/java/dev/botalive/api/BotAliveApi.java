package dev.botalive.api;

import dev.botalive.api.ai.GoalRegistry;
import dev.botalive.api.bot.BotManager;
import dev.botalive.api.command.SubcommandRegistry;

/**
 * Vstupní bod veřejného API pluginu BotAlive.
 *
 * <p>Instanci získají cizí pluginy přes {@link BotAliveProvider#get()} nebo přes
 * Bukkit {@code ServicesManager}. API je navrženo tak, aby na něm šlo stavět bez
 * závislosti na implementačních detailech (síťová vrstva, databáze, ...).</p>
 */
public interface BotAliveApi {

    /**
     * @return správce životního cyklu všech botů (vytváření, mazání, vyhledávání)
     */
    BotManager botManager();

    /**
     * @return registr AI cílů; umožňuje cizím pluginům přidávat vlastní chování botů
     */
    GoalRegistry goalRegistry();

    /**
     * @return registr podpříkazů {@code /botalive}; umožňuje cizím pluginům
     *         přidat vlastní podpříkazy včetně tab-complete
     */
    SubcommandRegistry subcommands();

    /**
     * @return verze pluginu BotAlive (např. {@code 1.0.0})
     */
    String version();
}
