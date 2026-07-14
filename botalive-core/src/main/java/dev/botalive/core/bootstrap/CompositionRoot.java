package dev.botalive.core.bootstrap;

import dev.botalive.api.BotAliveApi;
import dev.botalive.api.BotAliveProvider;
import dev.botalive.core.ai.GoalRegistryImpl;
import dev.botalive.core.ai.goals.BuildShelterGoal;
import dev.botalive.core.ai.goals.CollectItemsGoal;
import dev.botalive.core.ai.goals.CombatGoal;
import dev.botalive.core.ai.goals.EatGoal;
import dev.botalive.core.ai.goals.ExploreGoal;
import dev.botalive.core.ai.goals.FollowPlayerGoal;
import dev.botalive.core.ai.goals.IdleGoal;
import dev.botalive.core.ai.goals.MineGoal;
import dev.botalive.core.ai.goals.ReturnHomeGoal;
import dev.botalive.core.ai.goals.SocializeGoal;
import dev.botalive.core.ai.goals.SurviveGoal;
import dev.botalive.core.ai.goals.WanderGoal;
import dev.botalive.core.bot.BotImpl;
import dev.botalive.core.bot.BotManagerImpl;
import dev.botalive.core.commands.BotAliveCommand;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.di.ServiceContainer;
import dev.botalive.core.pathfinding.NavigationService;
import dev.botalive.core.persistence.BotRepository;
import dev.botalive.core.persistence.Database;
import dev.botalive.core.persistence.SchemaMigrator;
import dev.botalive.core.scheduler.BotTickEngine;
import dev.botalive.core.scheduler.MainThreadBridge;
import dev.botalive.core.world.WorldViewRegistry;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Kompoziční kořen – jediné místo, kde se plugin „skládá dohromady".
 *
 * <p>Explicitní constructor injection: pořadí konstrukce je zároveň grafem
 * závislostí a chybné zapojení selže okamžitě při startu. Kontejner
 * ({@link ServiceContainer}) drží singletony pro fázi vypnutí a příkazy.</p>
 */
public final class CompositionRoot {

    private final ServiceContainer container = new ServiceContainer();

    /**
     * Sestaví všechny služby pluginu.
     *
     * @param plugin instance pluginu
     * @param config načtená konfigurace
     */
    public CompositionRoot(JavaPlugin plugin, BotAliveConfig config) {
        container.register(BotAliveConfig.class, config);

        // Infrastruktura.
        MainThreadBridge bridge = container.register(MainThreadBridge.class,
                new MainThreadBridge(plugin));
        BotTickEngine tickEngine = container.register(BotTickEngine.class,
                new BotTickEngine(config.performance().tickThreads()));
        NavigationService navigation = container.register(NavigationService.class,
                new NavigationService(config.performance().pathfindingThreads()));
        WorldViewRegistry worldViews = container.register(WorldViewRegistry.class,
                new WorldViewRegistry(bridge, config.performance()));

        // Persistence.
        Database database = container.register(Database.class,
                new Database(config.persistence(), plugin.getDataFolder()));
        new SchemaMigrator(database).migrate();
        BotRepository repository = container.register(BotRepository.class,
                new BotRepository(database));

        // AI cíle.
        GoalRegistryImpl goalRegistry = container.register(GoalRegistryImpl.class,
                new GoalRegistryImpl());
        registerBuiltInGoals(goalRegistry);

        // Boti.
        BotImpl.SharedServices services = new BotImpl.SharedServices(
                config, worldViews, bridge, tickEngine, navigation, repository);
        BotManagerImpl botManager = container.register(BotManagerImpl.class,
                new BotManagerImpl(config, repository, goalRegistry, services));

        // Veřejné API.
        BotAliveApi api = container.register(BotAliveApi.class, new BotAliveApiImpl(
                botManager, goalRegistry, plugin.getPluginMeta().getVersion()));
        BotAliveProvider.register(api);

        // Bukkit integrace.
        container.register(ServerEventListener.class,
                new ServerEventListener(worldViews, botManager));
        container.register(BotAliveCommand.class,
                new BotAliveCommand(botManager, goalRegistry, repository));
    }

    /** Vestavěná sada cílů – každý bot dostává vlastní instance. */
    private static void registerBuiltInGoals(GoalRegistryImpl registry) {
        registry.register("idle", bot -> new IdleGoal());
        registry.register("wander", bot -> new WanderGoal());
        registry.register("explore", bot -> new ExploreGoal());
        registry.register("eat", bot -> new EatGoal());
        registry.register("survive", bot -> new SurviveGoal());
        registry.register("combat", bot -> new CombatGoal());
        registry.register("collect", bot -> new CollectItemsGoal());
        registry.register("socialize", bot -> new SocializeGoal());
        registry.register("mine", bot -> new MineGoal());
        registry.register("home", bot -> new ReturnHomeGoal());
        registry.register("shelter", bot -> new BuildShelterGoal());
        registry.register("follow", bot -> new FollowPlayerGoal());
    }

    /**
     * @param type typ služby
     * @param <T>  typ služby
     * @return registrovaná služba
     */
    public <T> T get(Class<T> type) {
        return container.get(type);
    }

    /**
     * Řízené vypnutí – v opačném pořadí závislostí.
     */
    public void shutdown() {
        get(BotManagerImpl.class).shutdownAll();
        get(BotTickEngine.class).shutdown();
        get(NavigationService.class).shutdown();
        get(Database.class).close();
        BotAliveProvider.register(null);
    }
}
