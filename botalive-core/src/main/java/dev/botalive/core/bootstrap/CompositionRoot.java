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

        // Fráze botů – jazyk dle konfigurace, šablony se exportují k úpravám.
        dev.botalive.core.chat.PhraseBankLoader.exportDefaults(plugin.getDataFolder());
        dev.botalive.core.chat.PhraseBank phrases = container.register(
                dev.botalive.core.chat.PhraseBank.class,
                dev.botalive.core.chat.PhraseBankLoader.load(
                        plugin.getDataFolder(), config.chat().language()));

        // Stanice (crafting, truhly, pec, obchod, enchant): na lokálním serveru
        // server-side simulace (§9), v režimu packet paketové container kliky
        // (§13) – jediné, co funguje na cizím serveru; lokálně slouží i k testu.
        boolean packetStations = config.network().packetWorldModel();
        dev.botalive.core.station.CraftingStation crafting = container.register(
                dev.botalive.core.station.CraftingStation.class,
                packetStations ? new dev.botalive.core.station.PacketCraftingStation()
                        : new CraftingService(bridge));
        dev.botalive.core.station.ChestStation containers = container.register(
                dev.botalive.core.station.ChestStation.class,
                packetStations ? new dev.botalive.core.station.PacketChestStation()
                        : new ContainerService(bridge));
        dev.botalive.core.station.TradeStation trades = container.register(
                dev.botalive.core.station.TradeStation.class,
                packetStations ? new dev.botalive.core.station.PacketTradeStation()
                        : new TradeService(bridge));
        dev.botalive.core.station.FurnaceStation furnaces = container.register(
                dev.botalive.core.station.FurnaceStation.class,
                packetStations ? new dev.botalive.core.station.PacketFurnaceStation()
                        : new FurnaceService(bridge));
        dev.botalive.core.station.EnchantStation enchanting = container.register(
                dev.botalive.core.station.EnchantStation.class,
                packetStations ? new dev.botalive.core.station.PacketEnchantStation()
                        : new EnchantService(bridge));
        dev.botalive.core.station.SmithingStation smithing = container.register(
                dev.botalive.core.station.SmithingStation.class,
                packetStations ? new dev.botalive.core.station.PacketSmithingStation()
                        : new dev.botalive.core.inventory.SmithingService(bridge));
        dev.botalive.core.pvp.PvpCoordinator pvp = container.register(
                dev.botalive.core.pvp.PvpCoordinator.class,
                new dev.botalive.core.pvp.PvpCoordinator(config.pvp()));
        dev.botalive.core.economy.MarketBoard market = container.register(
                dev.botalive.core.economy.MarketBoard.class,
                new dev.botalive.core.economy.MarketBoard());
        dev.botalive.core.tame.TameService taming = container.register(
                dev.botalive.core.tame.TameService.class,
                new dev.botalive.core.tame.TameService(bridge));
        dev.botalive.core.social.SocialGraph socialGraph = container.register(
                dev.botalive.core.social.SocialGraph.class,
                new dev.botalive.core.social.SocialGraph());

        // AI cíle.
        GoalRegistryImpl goalRegistry = container.register(GoalRegistryImpl.class,
                new GoalRegistryImpl());
        dev.botalive.core.inventory.AnvilService anvils = container.register(
                dev.botalive.core.inventory.AnvilService.class,
                new dev.botalive.core.inventory.AnvilService(bridge));
        registerBuiltInGoals(goalRegistry, crafting, containers, trades, furnaces,
                enchanting, smithing, pvp, taming, anvils, market, socialGraph);

        // Block-state a item mappery pro klientský world model (jen režim
        // packet): přesné tabulky z registrů hostitelského serveru jsou správné
        // jen při shodě protokolu hostitele s protokolem botů – překládá-li
        // Via, dostává bot pakety ve svém vlastním formátu a registry jiné
        // verze by daly špatná ID. Jinak degradovaný fallback.
        dev.botalive.core.world.state.BlockStateMapper stateMapper = null;
        dev.botalive.core.world.state.ItemMapper itemMapper = null;
        if (config.network().packetWorldModel()) {
            if (dev.botalive.core.via.ViaCompat.hostMatchesBotProtocol()) {
                stateMapper = dev.botalive.core.world.state.ReflectionBlockStateMapper.tryCreate()
                        .orElseGet(dev.botalive.core.world.state.FallbackBlockStateMapper::new);
                itemMapper = dev.botalive.core.world.state.ReflectionItemMapper.tryCreate()
                        .orElse(null);
            } else {
                LOG.warn("Registry hostitelského serveru ({}) neodpovídají protokolu botů ({}) – "
                                + "block-state i item mapování poběží v degradovaném fallbacku.",
                        org.bukkit.Bukkit.getMinecraftVersion(),
                        dev.botalive.core.via.ViaCompat.botVersion());
                stateMapper = new dev.botalive.core.world.state.FallbackBlockStateMapper();
            }
        }

        // Boti.
        dev.botalive.core.social.CrimeLog crimeLog = container.register(
                dev.botalive.core.social.CrimeLog.class, new dev.botalive.core.social.CrimeLog());
        dev.botalive.core.settlement.SettlementService settlements = container.register(
                dev.botalive.core.settlement.SettlementService.class,
                new dev.botalive.core.settlement.SettlementService(
                        config.settlement(), repository));
        settlements.load();
        BotImpl.SharedServices services = new BotImpl.SharedServices(
                config, worldViews, bridge, tickEngine, navigation, repository,
                phrases, stateMapper, itemMapper, crimeLog, settlements,
                socialGraph, market);
        BotManagerImpl botManager = container.register(BotManagerImpl.class,
                new BotManagerImpl(config, repository, goalRegistry, services));
        pvp.attach(botManager);
        settlements.attach(botManager);
        market.attach(botManager);
        socialGraph.attach(botManager);

        // Veřejné API.
        BotAliveApi api = container.register(BotAliveApi.class, new BotAliveApiImpl(
                botManager, goalRegistry, plugin.getPluginMeta().getVersion()));
        BotAliveProvider.register(api);

        // Bukkit integrace.
        container.register(ServerEventListener.class,
                new ServerEventListener(worldViews, botManager, pvp));
        container.register(BotAliveCommand.class,
                new BotAliveCommand(botManager, goalRegistry, repository, config, settlements));
    }

    /** Vestavěná sada cílů – každý bot dostává vlastní instance. */
    private static void registerBuiltInGoals(GoalRegistryImpl registry,
                                             dev.botalive.core.station.CraftingStation crafting,
                                             dev.botalive.core.station.ChestStation containers,
                                             dev.botalive.core.station.TradeStation trades,
                                             dev.botalive.core.station.FurnaceStation furnaces,
                                             dev.botalive.core.station.EnchantStation enchanting,
                                             dev.botalive.core.station.SmithingStation smithing,
                                             dev.botalive.core.pvp.PvpCoordinator pvp,
                                             dev.botalive.core.tame.TameService taming,
                                             dev.botalive.core.inventory.AnvilService anvils,
                                             dev.botalive.core.economy.MarketBoard market,
                                             dev.botalive.core.social.SocialGraph socialGraph) {
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
        registry.register("shelter", bot -> new BuildShelterGoal());
        registry.register("house", bot -> new BuildHouseGoal());
        registry.register("follow", bot -> new FollowPlayerGoal());
        registry.register("craft", bot -> new CraftGoal(crafting));
        registry.register("farm", bot -> new FarmGoal());
        registry.register("sleep", bot -> new SleepGoal());
        registry.register("stash", bot -> new StashGoal(containers));
        registry.register("steal", bot -> new dev.botalive.core.ai.goals.StealGoal(containers));
        registry.register("rob", bot -> new dev.botalive.core.ai.goals.RobGoal(pvp));
        registry.register("repair", bot -> new dev.botalive.core.ai.goals.RepairGoal(anvils));
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
        registry.register("tame", bot -> new dev.botalive.core.ai.goals.TameGoal(taming));
        registry.register("recover", bot -> new dev.botalive.core.ai.goals.RecoverItemsGoal());
        registry.register("maintain", bot -> new dev.botalive.core.ai.goals.MaintainHomeGoal());
        registry.register("sell", bot -> new dev.botalive.core.ai.goals.SellGoal(market, socialGraph));
        registry.register("buy", bot -> new dev.botalive.core.ai.goals.BuyGoal(market));
        registry.register("reconcile", bot -> new dev.botalive.core.ai.goals.ReconcileGoal(socialGraph));
        registry.register("guard", bot -> new dev.botalive.core.ai.goals.GuardGoal());
        registry.register("nether", bot -> new dev.botalive.core.ai.goals.NetherGoal(containers));
        registry.register("drink", bot -> new dev.botalive.core.ai.goals.DrinkPotionGoal());
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
