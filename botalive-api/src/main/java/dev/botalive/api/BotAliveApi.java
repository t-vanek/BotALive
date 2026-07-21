package dev.botalive.api;

import dev.botalive.api.ai.GoalRegistry;
import dev.botalive.api.bot.BotManager;
import dev.botalive.api.command.SubcommandRegistry;
import dev.botalive.api.persistence.BotDataStore;
import dev.botalive.api.role.RoleRegistry;

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
     * @return registr profesí; umožňuje cizím pluginům přidat vlastní role
     *         (zaměření AI cílů) nad rámec vestavěného {@link dev.botalive.api.role.BotRole}
     */
    RoleRegistry roles();

    /**
     * @return perzistentní key-value úložiště vázané na bota; umožňuje cizím
     *         pluginům ukládat vlastní data (mažou se s botem při purge)
     */
    BotDataStore dataStore();

    /**
     * @return verze pluginu BotAlive (např. {@code 1.0.0})
     */
    String version();
}
