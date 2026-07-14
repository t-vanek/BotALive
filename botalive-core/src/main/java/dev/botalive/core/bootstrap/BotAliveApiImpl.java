package dev.botalive.core.bootstrap;

import dev.botalive.api.BotAliveApi;
import dev.botalive.api.ai.GoalRegistry;
import dev.botalive.api.bot.BotManager;

/**
 * Implementace veřejného API – tenká fasáda nad interními službami.
 */
public final class BotAliveApiImpl implements BotAliveApi {

    private final BotManager botManager;
    private final GoalRegistry goalRegistry;
    private final String version;

    /**
     * @param botManager   manager botů
     * @param goalRegistry registr cílů
     * @param version      verze pluginu
     */
    public BotAliveApiImpl(BotManager botManager, GoalRegistry goalRegistry, String version) {
        this.botManager = botManager;
        this.goalRegistry = goalRegistry;
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
    public String version() {
        return version;
    }
}
