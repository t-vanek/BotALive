package dev.botalive.core.bootstrap;

import dev.botalive.api.BotAliveApi;
import dev.botalive.api.ai.GoalRegistry;
import dev.botalive.api.bot.BotManager;
import dev.botalive.api.command.SubcommandRegistry;

/**
 * Implementace veřejného API – tenká fasáda nad interními službami.
 */
public final class BotAliveApiImpl implements BotAliveApi {

    private final BotManager botManager;
    private final GoalRegistry goalRegistry;
    private final SubcommandRegistry subcommands;
    private final String version;

    /**
     * @param botManager   manager botů
     * @param goalRegistry registr cílů
     * @param subcommands  registr podpříkazů
     * @param version      verze pluginu
     */
    public BotAliveApiImpl(BotManager botManager, GoalRegistry goalRegistry,
                           SubcommandRegistry subcommands, String version) {
        this.botManager = botManager;
        this.goalRegistry = goalRegistry;
        this.subcommands = subcommands;
        this.version = version;
    }

    @Override
    public BotManager botManager() {
        return botManager;
    }

    @Override
    public GoalRegistry goalRegistry() {
        return goalRegistry;
    }

    @Override
    public SubcommandRegistry subcommands() {
        return subcommands;
    }

    @Override
    public String version() {
        return version;
    }
}
