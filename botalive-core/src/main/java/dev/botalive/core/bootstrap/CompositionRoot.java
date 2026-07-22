package dev.botalive.core.bootstrap;

import dev.botalive.api.BotAliveApi;
import dev.botalive.api.BotAliveProvider;
import dev.botalive.core.ai.GoalRegistryImpl;
import dev.botalive.core.ai.goals.BuildHouseGoal;
import dev.botalive.core.ai.goals.BuildShelterGoal;
import dev.botalive.core.ai.goals.CollectItemsGoal;
import dev.botalive.core.ai.goals.CombatGoal;
import dev.botalive.core.ai.goals.CraftGoal;
import dev.botalive.core.ai.goals.EatGoal;
import dev.botalive.core.ai.goals.ExploreGoal;
import dev.botalive.core.ai.goals.FarmGoal;
import dev.botalive.core.ai.goals.FollowPlayerGoal;
import dev.botalive.core.ai.goals.IdleGoal;
import dev.botalive.core.ai.goals.MineGoal;
import dev.botalive.core.ai.goals.ReturnHomeGoal;
import dev.botalive.core.ai.goals.SleepGoal;
import dev.botalive.core.ai.goals.SocializeGoal;
import dev.botalive.core.ai.goals.StashGoal;
import dev.botalive.core.ai.goals.SurviveGoal;
import dev.botalive.core.ai.goals.WanderGoal;
import dev.botalive.core.ai.goals.BoatRideGoal;
import dev.botalive.core.ai.goals.EnchantGoal;
import dev.botalive.core.ai.goals.FishGoal;
import dev.botalive.core.ai.goals.HuntGoal;
import dev.botalive.core.ai.goals.MinecartRideGoal;
import dev.botalive.core.ai.goals.SmeltGoal;
import dev.botalive.core.ai.goals.TradeGoal;
import dev.botalive.core.crafting.CraftingService;
import dev.botalive.core.inventory.ContainerService;
import dev.botalive.core.inventory.EnchantService;
import dev.botalive.core.inventory.FurnaceService;
import dev.botalive.core.trade.TradeService;
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

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(CompositionRoot.class);

    private final ServiceContainer container = new ServiceContainer();

    /** Vestavěná HTTP gateway (jen když je zapnutá); {@code null} jinak. */
    private dev.botalive.core.gateway.MojangGatewayServer gatewayServer;

    /**
     * Sestaví všechny služby pluginu.
     *
     * @param plugin instance pluginu
     * @param config načtená konfigurace
     */
    public CompositionRoot(JavaPlugin plugin, BotAliveConfig config) {
        container.register(BotAliveConfig.class, config);

        // Vlastní ověřovací proces botů: autorita vydává a ověřuje pověření.
        // Vzniká vždy (levné) – vypínače v gateway konfiguraci řídí chování.
        byte[] gatewaySecret = dev.botalive.core.gateway.GatewaySecret.resolve(
                config.gateway().secret(), plugin.getDataFolder());
        dev.botalive.core.gateway.CredentialAuthority authority = container.register(
                dev.botalive.core.gateway.CredentialAuthority.class,
                new dev.botalive.core.gateway.CredentialAuthority(gatewaySecret,
                        config.gateway().tokenTtlMs(), true));
        // Volitelná HTTP gateway ve tvaru Mojang session API (online-mode/proxy/fleet).
        if (config.gateway().enabled() && config.gateway().httpEnabled()) {
            // Volitelná proxy na skutečný Mojang – online-mode server pak ověří
            // zároveň boty (lokálně) i reální hráče (přes Mojang).
            dev.botalive.core.gateway.MojangProxy proxy = config.gateway().mojangProxy()
                    ? new dev.botalive.core.gateway.MojangProxy(config.gateway().mojangHost(),
                            java.time.Duration.ofSeconds(6))
                    : null;
            dev.botalive.core.gateway.MojangGatewayServer server =
                    new dev.botalive.core.gateway.MojangGatewayServer(
                            config.gateway().bind(), config.gateway().port(), authority, proxy);
            try {
                server.start();
                this.gatewayServer = server;
                if (proxy != null) {
                    LOG.info("HTTP gateway: proxy na Mojang zapnutá ({}) – reální hráči se ověří "
                            + "u Mojangu, boti lokálně.", config.gateway().mojangHost());
                }
            } catch (java.io.IOException e) {
                LOG.warn("HTTP gateway se nepodařilo spustit ({}:{}): {} – server-side pojistka "
                        + "loginu funguje i bez ní.", config.gateway().bind(),
                        config.gateway().port(), e.toString());
            }
        }

        // Velocity forwarding: jednorázová startovní diagnostika (samotné
        // odpovídání řeší per-connection listener v BotConnection).
        BotAliveConfig.Network.Velocity velocity = config.network().velocity();
        if (velocity.enabled()) {
            if (velocity.secret().isBlank()) {
                LOG.warn("network.velocity.enabled=true, ale secret je prázdný – boti se za "
                        + "Velocity neověří. Doplň forwarding.secret z Velocity.");
            } else {
                LOG.info("Velocity modern forwarding zapnutý – boti se ověří u offline-mode "
                        + "backendu za Velocity proxy.");
            }
        }

        // Infrastruktura.
        MainThreadBridge bridge = container.register(MainThreadBridge.class,
                new MainThreadBridge(plugin));
        BotTickEngine tickEngine = container.register(BotTickEngine.class,
                new BotTickEngine(config.performance().tickThreads()));
        NavigationService navigation = container.register(NavigationService.class,
                new NavigationService(config.performance().pathfindingThreads(),
                        config.pathfinding().nodeBudget(), config.pathfinding().timeBudgetMs(),
                        config.pathfinding().farCorridor(),
                        config.pathfinding().plannedActions()));
        WorldViewRegistry worldViews = container.register(WorldViewRegistry.class,
                new WorldViewRegistry(bridge, config.performance()));

        // Persistence.
        Database database = container.register(Database.class,
                new Database(config.persistence(), plugin.getDataFolder()));
        new SchemaMigrator(database).migrate();
        BotRepository repository = container.register(BotRepository.class,
                new BotRepository(database));

        // Fráze botů – jazyk dle konfigurace, šablony se exportují k úpravám.
        dev.botalive.core.chat.PhraseBankLoader.exportDefaults(plugin.getDataFolder());
        dev.botalive.core.chat.PhraseBank phrases = container.register(
                dev.botalive.core.chat.PhraseBank.class,
                dev.botalive.core.chat.PhraseBankLoader.load(
                        plugin.getDataFolder(), config.chat().language()));

        // Stanice (crafting, truhly, pec, obchod, enchant): server-side simulace
        // na lokálním serveru (§9) – bot je klient, ale čte/píše autoritativně
        // přes Bukkit na serveru, kde plugin běží.
        dev.botalive.core.station.CraftingStation crafting = container.register(
                dev.botalive.core.station.CraftingStation.class,
                new CraftingService(bridge));
        dev.botalive.core.station.ChestStation containers = container.register(
                dev.botalive.core.station.ChestStation.class,
                new ContainerService(bridge));
        dev.botalive.core.station.TradeStation trades = container.register(
                dev.botalive.core.station.TradeStation.class,
                new TradeService(bridge));
        dev.botalive.core.station.FurnaceStation furnaces = container.register(
                dev.botalive.core.station.FurnaceStation.class,
                new FurnaceService(bridge));
        dev.botalive.core.station.EnchantStation enchanting = container.register(
                dev.botalive.core.station.EnchantStation.class,
                new EnchantService(bridge));
        dev.botalive.core.station.SmithingStation smithing = container.register(
                dev.botalive.core.station.SmithingStation.class,
                new dev.botalive.core.inventory.SmithingService(bridge));
        dev.botalive.core.station.BrewingStation brewing = container.register(
                dev.botalive.core.station.BrewingStation.class,
                new dev.botalive.core.inventory.BrewingService(bridge));
        dev.botalive.core.pvp.PvpCoordinator pvp = container.register(
                dev.botalive.core.pvp.PvpCoordinator.class,
                new dev.botalive.core.pvp.PvpCoordinator(config.pvp()));
        dev.botalive.core.economy.MarketBoard market = container.register(
                dev.botalive.core.economy.MarketBoard.class,
                new dev.botalive.core.economy.MarketBoard());
        dev.botalive.core.tame.TameService taming = container.register(
                dev.botalive.core.tame.TameService.class,
                new dev.botalive.core.tame.TameService(bridge));
        dev.botalive.core.husbandry.BreedService breeding = container.register(
                dev.botalive.core.husbandry.BreedService.class,
                new dev.botalive.core.husbandry.BreedService(bridge));
        dev.botalive.core.social.SocialGraph socialGraph = container.register(
                dev.botalive.core.social.SocialGraph.class,
                new dev.botalive.core.social.SocialGraph());

        // AI cíle.
        GoalRegistryImpl goalRegistry = container.register(GoalRegistryImpl.class,
                new GoalRegistryImpl());
        dev.botalive.core.inventory.AnvilService anvils = container.register(
                dev.botalive.core.inventory.AnvilService.class,
                new dev.botalive.core.inventory.AnvilService(bridge));
        dev.botalive.core.inventory.GrindstoneService grindstones = container.register(
                dev.botalive.core.inventory.GrindstoneService.class,
                new dev.botalive.core.inventory.GrindstoneService(bridge));
        // Registrace cílů proběhne níže – až po vzniku služeb sídel a diplomacie,
        // které některé cíle dostávají v konstruktoru.

        // Boti.
        dev.botalive.core.social.CrimeLog crimeLog = container.register(
                dev.botalive.core.social.CrimeLog.class, new dev.botalive.core.social.CrimeLog());
        dev.botalive.core.settlement.SettlementService settlements = container.register(
                dev.botalive.core.settlement.SettlementService.class,
                new dev.botalive.core.settlement.SettlementService(
                        config.settlement(), repository));
        settlements.load();
        dev.botalive.core.settlement.DiplomacyService diplomacy = container.register(
                dev.botalive.core.settlement.DiplomacyService.class,
                new dev.botalive.core.settlement.DiplomacyService(
                        config.settlement().war(), config.pvp(), settlements, repository));
        diplomacy.load();
        dev.botalive.core.economy.EmploymentService employmentService = container.register(
                dev.botalive.core.economy.EmploymentService.class,
                new dev.botalive.core.economy.EmploymentService(
                        config.economy().employment(), repository));
        employmentService.load();
        registerBuiltInGoals(goalRegistry, crafting, containers, trades, furnaces,
                enchanting, smithing, brewing, pvp, taming, breeding, anvils, grindstones,
                market, socialGraph, diplomacy, employmentService);
        // Registr profesí (vestavěné role předregistrované; cizí přidává plugin).
        dev.botalive.core.role.RoleRegistryImpl roles = container.register(
                dev.botalive.core.role.RoleRegistryImpl.class,
                new dev.botalive.core.role.RoleRegistryImpl());
        // Registr kategorií vzpomínek (cizí druhy pluginů, vlastní rozpad).
        dev.botalive.core.memory.MemoryKindRegistryImpl memoryKinds = container.register(
                dev.botalive.core.memory.MemoryKindRegistryImpl.class,
                new dev.botalive.core.memory.MemoryKindRegistryImpl());
        BotImpl.SharedServices services = new BotImpl.SharedServices(
                config, worldViews, bridge, tickEngine, navigation, repository,
                phrases, crimeLog, settlements,
                diplomacy, socialGraph, market, employmentService, authority, roles, memoryKinds);
        BotManagerImpl botManager = container.register(BotManagerImpl.class,
                new BotManagerImpl(config, repository, goalRegistry, services));
        pvp.attach(botManager);
        settlements.attach(botManager);
        diplomacy.attach(botManager);
        market.attach(botManager);
        socialGraph.attach(botManager);
        employmentService.attach(botManager);

        // Registr cizích podpříkazů /botalive (vestavěná jména jsou vyhrazená).
        dev.botalive.core.commands.SubcommandRegistryImpl subcommands = container.register(
                dev.botalive.core.commands.SubcommandRegistryImpl.class,
                new dev.botalive.core.commands.SubcommandRegistryImpl(
                        BotAliveCommand.builtInSubcommands()));

        // Úložiště dat cizích pluginů (namespaced key-value na bota).
        dev.botalive.core.persistence.BotDataStoreImpl dataStore = container.register(
                dev.botalive.core.persistence.BotDataStoreImpl.class,
                new dev.botalive.core.persistence.BotDataStoreImpl(database));

        // Registr taktických tasků cizích pluginů.
        dev.botalive.core.tasks.TaskRegistryImpl tasks = container.register(
                dev.botalive.core.tasks.TaskRegistryImpl.class,
                new dev.botalive.core.tasks.TaskRegistryImpl());

        // Veřejné API.
        BotAliveApi api = container.register(BotAliveApi.class, new BotAliveApiImpl(
                botManager, goalRegistry, subcommands, roles, memoryKinds, dataStore, tasks,
                plugin.getPluginMeta().getVersion()));
        BotAliveProvider.register(api);

        // Bukkit integrace.
        container.register(ServerEventListener.class,
                new ServerEventListener(worldViews, botManager, pvp, diplomacy,
                        employmentService));
        // Server-side pojistka proti zneužití identity bota při přihlášení.
        container.register(dev.botalive.core.gateway.BotLoginGuard.class,
                new dev.botalive.core.gateway.BotLoginGuard(botManager, authority, config.gateway()));
        container.register(BotAliveCommand.class,
                new BotAliveCommand(botManager, goalRegistry, repository, config, settlements,
                        diplomacy, employmentService, navigation, subcommands, roles));
    }

    /** Vestavěná sada cílů – každý bot dostává vlastní instance. */
    private static void registerBuiltInGoals(GoalRegistryImpl registry,
                                             dev.botalive.core.station.CraftingStation crafting,
                                             dev.botalive.core.station.ChestStation containers,
                                             dev.botalive.core.station.TradeStation trades,
                                             dev.botalive.core.station.FurnaceStation furnaces,
                                             dev.botalive.core.station.EnchantStation enchanting,
                                             dev.botalive.core.station.SmithingStation smithing,
                                             dev.botalive.core.station.BrewingStation brewing,
                                             dev.botalive.core.pvp.PvpCoordinator pvp,
                                             dev.botalive.core.tame.TameService taming,
                                             dev.botalive.core.husbandry.BreedService breeding,
                                             dev.botalive.core.inventory.AnvilService anvils,
                                             dev.botalive.core.inventory.GrindstoneService grindstones,
                                             dev.botalive.core.economy.MarketBoard market,
                                             dev.botalive.core.social.SocialGraph socialGraph,
                                             dev.botalive.core.settlement.DiplomacyService diplomacy,
                                             dev.botalive.core.economy.EmploymentService employment) {
        registry.register("idle", bot -> new IdleGoal());
        registry.register("wander", bot -> new WanderGoal());
        registry.register("explore", bot -> new ExploreGoal());
        registry.register("eat", bot -> new EatGoal());
        registry.register("survive", bot -> new SurviveGoal());
        registry.register("creeper-dodge", bot -> new dev.botalive.core.ai.goals.CreeperDodgeGoal());
        registry.register("combat", bot -> new CombatGoal());
        registry.register("collect", bot -> new CollectItemsGoal());
        registry.register("socialize", bot -> new SocializeGoal());
        registry.register("share", bot -> new dev.botalive.core.ai.goals.ShareGoal());
        registry.register("mine", bot -> new MineGoal());
        registry.register("home", bot -> new ReturnHomeGoal());
        registry.register("escape", bot -> new dev.botalive.core.ai.goals.EscapeGoal());
        registry.register("communal-build",
                bot -> new dev.botalive.core.ai.goals.CommunalBuildGoal(crafting, containers));
        registry.register("settlement-roads",
                bot -> new dev.botalive.core.ai.goals.SettlementRoadsGoal());
        registry.register("settlement-fences",
                bot -> new dev.botalive.core.ai.goals.SettlementFenceGoal(crafting));
        registry.register("settlement-walls",
                bot -> new dev.botalive.core.ai.goals.SettlementWallGoal(crafting));
        registry.register("pen", bot -> new dev.botalive.core.ai.goals.PenGoal(crafting));
        registry.register("camp", bot -> new dev.botalive.core.ai.goals.CampGoal());
        registry.register("shelter", bot -> new BuildShelterGoal());
        registry.register("house", bot -> new BuildHouseGoal());
        registry.register("follow", bot -> new FollowPlayerGoal());
        registry.register("craft", bot -> new CraftGoal(crafting));
        registry.register("farm", bot -> new FarmGoal());
        registry.register("sleep", bot -> new SleepGoal());
        registry.register("stash", bot -> new StashGoal(containers, diplomacy));
        registry.register("granary", bot -> new dev.botalive.core.ai.goals.GranaryGoal(containers));
        registry.register("supply", bot -> new dev.botalive.core.ai.goals.SupplyGoal(containers));
        registry.register("steal", bot -> new dev.botalive.core.ai.goals.StealGoal(containers));
        registry.register("rob", bot -> new dev.botalive.core.ai.goals.RobGoal(pvp));
        registry.register("repair",
                bot -> new dev.botalive.core.ai.goals.RepairGoal(anvils, grindstones));
        registry.register("compost", bot -> new dev.botalive.core.ai.goals.CompostGoal());
        registry.register("boat", bot -> new BoatRideGoal());
        registry.register("minecart", bot -> new MinecartRideGoal());
        registry.register("trade", bot -> new TradeGoal(trades));
        registry.register("hunt", bot -> new HuntGoal());
        registry.register("fish", bot -> new FishGoal());
        registry.register("smelt", bot -> new SmeltGoal(furnaces));
        registry.register("enchant", bot -> new EnchantGoal(enchanting));
        registry.register("smith", bot -> new dev.botalive.core.ai.goals.SmithGoal(smithing));
        registry.register("pvp", bot -> new dev.botalive.core.ai.goals.PvpGoal(pvp));
        registry.register("war-raid",
                bot -> new dev.botalive.core.ai.goals.WarRaidGoal(diplomacy, pvp));
        registry.register("bodyguard",
                bot -> new dev.botalive.core.ai.goals.BodyguardGoal(employment, pvp));
        registry.register("deliver-work",
                bot -> new dev.botalive.core.ai.goals.WorkDeliveryGoal(employment));
        registry.register("tame", bot -> new dev.botalive.core.ai.goals.TameGoal(taming));
        registry.register("breed", bot -> new dev.botalive.core.ai.goals.BreedGoal(breeding));
        registry.register("shear", bot -> new dev.botalive.core.ai.goals.ShearGoal());
        registry.register("recover", bot -> new dev.botalive.core.ai.goals.RecoverItemsGoal());
        registry.register("maintain", bot -> new dev.botalive.core.ai.goals.MaintainHomeGoal());
        registry.register("sell", bot -> new dev.botalive.core.ai.goals.SellGoal(market, socialGraph));
        registry.register("buy", bot -> new dev.botalive.core.ai.goals.BuyGoal(market));
        registry.register("reconcile", bot -> new dev.botalive.core.ai.goals.ReconcileGoal(socialGraph));
        registry.register("guard", bot -> new dev.botalive.core.ai.goals.GuardGoal());
        registry.register("build-guard", bot -> new dev.botalive.core.ai.goals.BuildGuardGoal());
        registry.register("nether", bot -> new dev.botalive.core.ai.goals.NetherGoal(containers));
        registry.register("drink", bot -> new dev.botalive.core.ai.goals.DrinkPotionGoal());
        registry.register("brew", bot -> new dev.botalive.core.ai.goals.BrewGoal(brewing));
        registry.register("wither-fight",
                bot -> new dev.botalive.core.ai.goals.WitherFightGoal());
        registry.register("stronghold",
                bot -> new dev.botalive.core.ai.goals.StrongholdSeekGoal());
        registry.register("end-travel", bot -> new dev.botalive.core.ai.goals.EndTravelGoal());
        registry.register("dragon-fight", bot -> new dev.botalive.core.ai.goals.DragonFightGoal());
        registry.register("end-harvest", bot -> new dev.botalive.core.ai.goals.EndHarvestGoal());
        registry.register("end-return", bot -> new dev.botalive.core.ai.goals.EndReturnGoal());
        registry.register("end-outer", bot -> new dev.botalive.core.ai.goals.EndOuterGoal(containers));
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
        if (gatewayServer != null) {
            gatewayServer.stop();
            gatewayServer = null;
        }
        get(BotManagerImpl.class).shutdownAll();
        // Až po odpojení všech botů: řízené vypnutí sdíleného netty event-loopu
        // (daemon skupina pluginu) – bez něj by ne-daemon vlákna knihovny držela
        // JVM po stopu serveru naživu (osiřelý java.exe se zámky světa).
        dev.botalive.core.network.BotEventLoop.shutdown();
        get(BotTickEngine.class).shutdown();
        get(NavigationService.class).shutdown();
        get(Database.class).close();
        BotAliveProvider.register(null);
    }
}
