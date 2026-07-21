package dev.botalive.core.bootstrap;

import dev.botalive.api.BotAliveApi;
import dev.botalive.api.ai.GoalRegistry;
import dev.botalive.api.bot.BotManager;
import dev.botalive.api.command.SubcommandRegistry;
import dev.botalive.api.persistence.BotDataStore;
import dev.botalive.api.role.RoleRegistry;
import dev.botalive.api.task.TaskRegistry;

/**
 * Implementace veřejného API – tenká fasáda nad interními službami.
 */
public final class BotAliveApiImpl implements BotAliveApi {

    private final BotManager botManager;
    private final GoalRegistry goalRegistry;
    private final SubcommandRegistry subcommands;
    private final RoleRegistry roles;
    private final BotDataStore dataStore;
    private final TaskRegistry tasks;
    private final String version;

    /**
     * @param botManager   manager botů
     * @param goalRegistry registr cílů
     * @param subcommands  registr podpříkazů
     * @param roles        registr profesí
     * @param dataStore    úložiště dat pluginů
     * @param tasks        registr taktických tasků
     * @param version      verze pluginu
     */
    public BotAliveApiImpl(BotManager botManager, GoalRegistry goalRegistry,
                           SubcommandRegistry subcommands, RoleRegistry roles,
                           BotDataStore dataStore, TaskRegistry tasks, String version) {
        this.botManager = botManager;
        this.goalRegistry = goalRegistry;
        this.subcommands = subcommands;
        this.roles = roles;
        this.dataStore = dataStore;
        this.tasks = tasks;
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
    public RoleRegistry roles() {
        return roles;
    }

    @Override
    public BotDataStore dataStore() {
        return dataStore;
    }

    @Override
    public TaskRegistry tasks() {
        return tasks;
    }

    @Override
    public String version() {
        return version;
    }
}
