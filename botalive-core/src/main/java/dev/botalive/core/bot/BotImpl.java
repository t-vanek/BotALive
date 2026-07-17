package dev.botalive.core.bot;

import dev.botalive.api.ai.Goal;
import dev.botalive.api.bot.Bot;
import dev.botalive.api.bot.BotLifecycleState;
import dev.botalive.api.bot.BotSnapshot;
import dev.botalive.api.economy.BotWallet;
import dev.botalive.api.event.BotChatEvent;
import dev.botalive.api.event.BotDiedEvent;
import dev.botalive.api.event.BotSpawnedEvent;
import dev.botalive.api.memory.BotMemory;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Personality;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.Brain;
import dev.botalive.core.chat.ChatEngine;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.combat.CombatController;
import dev.botalive.core.combat.CombatDifficulty;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.entity.EntityTracker;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.human.Humanizer;
import dev.botalive.core.inventory.ClientInventory;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.network.BotActions;
import dev.botalive.core.network.BotClientState;
import dev.botalive.core.network.BotConnection;
import dev.botalive.core.network.BotSessionListener;
import dev.botalive.core.network.MovementSender;
import dev.botalive.core.network.NetworkEvents;
import dev.botalive.core.network.TeleportSync;
import dev.botalive.core.pathfinding.NavigationService;
import dev.botalive.core.pathfinding.Navigator;
import dev.botalive.core.persistence.BotRepository;
import dev.botalive.core.physics.BotPhysics;
import dev.botalive.core.physics.CrowdAvoidance;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.scheduler.BotTickEngine;
import dev.botalive.core.scheduler.MainThreadBridge;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.vehicle.VehicleController;
import dev.botalive.core.world.WorldView;
import dev.botalive.core.world.WorldViewRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Jádro jednoho bota – skládá všechny subsystémy do živé entity.
 *
 * <p><b>Threading model:</b> mutable herní stav (fyzika, mozek, navigace) je
 * confinovaný na tick vlákno bota ({@link BotTickEngine}); síťová vrstva
 * komunikuje přes thread-safe schránky ({@link BotClientState}, fronty,
 * {@link EntityTracker}); server-side data čte AI přes {@link ServerSideView}
 * snapshoty. Veřejné API metody jsou bezpečné z libovolného vlákna.</p>
 */
public final class BotImpl implements Bot, BotContext, NetworkEvents,
        dev.botalive.core.chat.ChatContext {

    private static final Logger LOG = LoggerFactory.getLogger(BotImpl.class);

    // --- Identita a služby (nemutabilní po konstrukci) ------------------------
    private final UUID id;
    private final String name;
    private final Personality personality;
    private final BotMemory memory;
    private final BotWallet wallet;
    private final BotStats stats;
    private final BotAliveConfig config;
    private final BotRandom rng;

    private final BotClientState clientState = new BotClientState();
    private final EntityTracker entities = new EntityTracker();
    private final ClientInventory clientInventory = new ClientInventory();
    private final dev.botalive.core.container.ContainerTracker containerTracker =
            new dev.botalive.core.container.ContainerTracker();
    private final dev.botalive.core.container.ContainerClicker containerClicker;
    private final dev.botalive.core.world.state.ItemMapper itemMapper;
    private final BotConnection connection;
    private final BotActions actions;
    private final MovementSender movementSender;
    private final Humanizer humanizer;
    private final Navigator navigator;
    private final CombatController combat;
    private final ChatEngine chat;
    private final InventoryHelper inventoryHelper;
    private final ServerSideView serverView;
    private final VehicleController vehicle;

    private final WorldViewRegistry worldViews;
    private final MainThreadBridge bridge;
    private final BotTickEngine tickEngine;
    private final BotRepository repository;
    private final SharedServices services;
    private final Brain brain;

    /** Klientský world model (jen v režimu {@code packet}, jinak {@code null}). */
    private final dev.botalive.core.world.PacketWorldManager packetWorlds;

    // --- Mutable stav (tick vlákno / volatile) ---------------------------------
    private final AtomicReference<BotLifecycleState> state =
            new AtomicReference<>(BotLifecycleState.CREATED);
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean removed = new AtomicBoolean();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();

    private volatile WorldView worldView;
    private volatile BotPhysics physics;
    private volatile CompletableFuture<Bot> spawnFuture;

    /** Profese bota – násobí utility souvisejících cílů (viz RoleProfiles). */
    private volatile dev.botalive.api.role.BotRole role = dev.botalive.api.role.BotRole.NONE;

    /** Pohyb vyžádaný cílem pro aktuální tick (přebíjí navigátor). */
    private MoveInput requestedMove;
    /** Probíhající zásah do terénu při zdolávání překážky (kopání/pokládání). */
    private dev.botalive.core.tasks.BotTask obstacleTask;
    /** Odpočet periodické kontroly brnění v hotbaru. */
    private int armorCheckTicks = 60;
    /** Životní ambice (cache; zdroj pravdy je paměť AMBITION). */
    private volatile dev.botalive.core.ai.Ambition ambition;
    private volatile dev.botalive.core.ai.Ambition.Progress ambitionProgress;
    private int ambitionRefreshTicks;

    /** Čeká se na první teleport po loginu/respawnu (pošle se PlayerLoaded). */
    private volatile boolean awaitingFirstTeleport = true;

    /** Smrt už byla obsloužena na tick vlákně (halt mozku, stop navigace). */
    private boolean deathHandled;

    /** Zámek tick smyčky – chrání stav confinovaný na tick vlákno před
     *  lifecycle zásahy z jiných vláken (disconnect, pause, remove). */
    private final Object tickLock = new Object();

    private int ticksSinceSnapshot;
    private int ticksSinceFlush;
    private Vec3 lastDistancePos;

    /**
     * Sestaví bota. Volá {@code BotManagerImpl} po načtení identity z databáze.
     *
     * @param id          UUID bota
     * @param name        jméno bota
     * @param personality osobnost (z DB nebo nově vygenerovaná)
     * @param memory      paměť (načtená z DB)
     * @param wallet      peněženka
     * @param stats       statistiky
     * @param goalFactory továrna instancí cílů pro tohoto bota
     * @param services    sdílené služby pluginu
     */
    public BotImpl(UUID id, String name, Personality personality, BotMemory memory,
                   BotWallet wallet, BotStats stats,
                   java.util.function.Function<Bot, List<Goal>> goalFactory,
                   SharedServices services) {
        this.id = id;
        this.name = name;
        this.personality = personality;
        this.memory = memory;
        this.wallet = wallet;
        this.stats = stats;
        this.config = services.config();
        this.worldViews = services.worldViews();
        this.bridge = services.bridge();
        this.tickEngine = services.tickEngine();
        this.repository = services.repository();
        this.services = services;

        this.rng = new BotRandom(id.getLeastSignificantBits() ^ System.nanoTime());
        this.itemMapper = services.itemMapper();
        this.packetWorlds = config.network().packetWorldModel()
                ? new dev.botalive.core.world.PacketWorldManager(services.stateMapper())
                : null;
        this.connection = new BotConnection(name, id, config.network(),
                new BotSessionListener(name, clientState, entities, clientInventory,
                        containerTracker, this, packetWorlds));
        this.containerClicker = new dev.botalive.core.container.ContainerClicker(
                connection, containerTracker);
        this.actions = new BotActions(connection, clientState);
        this.movementSender = new MovementSender(connection, clientState);
        this.humanizer = new Humanizer(rng, personality);
        this.navigator = new Navigator(services.navigation(), actions, rng, personality);
        this.navigator.dangerSupplier(this::dangerMemories);
        this.inventoryHelper = new InventoryHelper(actions);
        this.combat = new CombatController(actions, humanizer, rng, personality, config.combat(),
                CombatDifficulty.fromConfig(config.ai().difficulty()), inventoryHelper);
        this.vehicle = new VehicleController(connection, clientState);
        this.serverView = new ServerSideView(id, bridge);
        this.chat = new ChatEngine(name, personality, rng, config.chat(),
                services.phrases(), this::deliverChat, this);
        this.brain = new Brain(this, goalFactory.apply(this),
                config.ai().decisionIntervalTicks(), config.ai().goalHysteresis(), rng,
                config.ai().dailyRhythm()
                        ? new dev.botalive.core.ai.DayRhythm(
                                personality.trait(dev.botalive.api.personality.Trait.LAZINESS))
                        : null);
    }

    /**
     * Intent vrstva: popis aktuální činnosti bota (pro chat a příkazy).
     *
     * @return věta v první osobě, nebo {@code null}
     */
    public String explainCurrentGoal() {
        Brain currentBrain = brain;
        return currentBrain == null ? null : currentBrain.explainCurrent();
    }

    @Override
    public dev.botalive.core.social.CrimeLog crimeLog() {
        return services.crimeLog();
    }

    /** Přepočte ambici a postup (cache pro mozek a příkazy). */
    private void refreshAmbition() {
        if (ambition == null) {
            var records = memory.recall(MemoryKind.AMBITION);
            if (!records.isEmpty()) {
                ambition = dev.botalive.core.ai.Ambition.parse(
                        records.getFirst().data().get("type"));
            }
        }
        if (ambition == null) {
            return;
        }
        var needs = dev.botalive.core.ai.BotNeeds.assess(serverView.latest());
        boolean hasHouse = memory.recall(MemoryKind.HOME).stream()
                .anyMatch(r -> "house".equals(r.data().get("type")));
        var snapshot = serverView.latest();
        boolean hasBed = snapshot != null
                && snapshot.hasItem(m -> m.name().endsWith("_BED"));
        ambitionProgress = ambition.progress(needs, hasHouse, hasBed, wallet.balance());
    }

    /**
     * Násobič utility cíle podle životní ambice (1.0 mimo ambici).
     *
     * @param goalId id cíle
     * @return násobič
     */
    public double ambitionWeight(String goalId) {
        var current = ambition;
        var progress = ambitionProgress;
        if (current == null || progress == null) {
            return 1.0;
        }
        return current.weight(goalId, progress.complete());
    }

    /** @return řádka „životní cíl" pro příkazy, nebo {@code null} */
    public String ambitionLine() {
        var current = ambition;
        var progress = ambitionProgress;
        if (current == null) {
            return null;
        }
        if (progress == null) {
            return current.label();
        }
        return current.label() + " (krok " + progress.step() + "/" + progress.total()
                + ": " + progress.label() + ")";
    }

    // ------------------------------------------------------------ ChatContext

    @Override
    public String describeActivity() {
        return explainCurrentGoal();
    }

    @Override
    public String describeLocation() {
        if (physics == null) {
            return null;
        }
        Vec3 pos = physics.position();
        String where = "jsem na %d, %d, %d".formatted(
                (int) pos.x(), (int) pos.y(), (int) pos.z());
        return worldView != null ? where + " (" + worldView.worldName() + ")" : where;
    }

    @Override
    public String describeInventory() {
        var snapshot = serverView.latest();
        if (snapshot == null) {
            return "ted nevim, co vlastne nesu";
        }
        Map<org.bukkit.Material, Integer> counts = new java.util.LinkedHashMap<>();
        var hotbar = snapshot.hotbar();
        int[] amounts = snapshot.hotbarCounts();
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] != null) {
                counts.merge(hotbar[i], amounts != null ? Math.max(amounts[i], 1) : 1,
                        Integer::sum);
            }
        }
        for (var material : snapshot.mainInventory()) {
            if (material != null) {
                counts.merge(material, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return "mam uplne prazdno";
        }
        StringBuilder sb = new StringBuilder("mam: ");
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(4)
                .forEach(e -> sb.append(e.getValue() > 1 ? e.getValue() + "x " : "")
                        .append(e.getKey().name().toLowerCase(java.util.Locale.ROOT))
                        .append(", "));
        return sb.substring(0, sb.length() - 2);
    }

    @Override
    public String describeVillage() {
        if (worldView == null || physics == null) {
            return null;
        }
        Vec3 pos = physics.position();
        return memory.recallNearest(MemoryKind.VILLAGE, worldView.worldName(),
                        (int) pos.x(), (int) pos.y(), (int) pos.z())
                .map(r -> "vesnice je u %d, %d, %d".formatted(r.x(), r.y(), r.z()))
                .orElse(null);
    }

    @Override
    public boolean followRequest(UUID requester) {
        double helpfulness = personality.trait(dev.botalive.api.personality.Trait.HELPFULNESS);
        boolean friend = memory.recallAbout(requester).stream()
                .anyMatch(r -> r.kind() == MemoryKind.FRIEND);
        if (!friend && helpfulness < 0.45) {
            return false;
        }
        return brain.forceGoal("follow");
    }

    @Override
    public boolean giveFoodRequest(UUID requester) {
        var snapshot = serverView.latest();
        if (snapshot == null
                || !snapshot.hasItem(dev.botalive.core.inventory.InventoryHelper::isFood)) {
            return false;
        }
        double helpfulness = personality.trait(dev.botalive.api.personality.Trait.HELPFULNESS);
        boolean friend = memory.recallAbout(requester).stream()
                .anyMatch(r -> r.kind() == MemoryKind.FRIEND);
        if (!friend && helpfulness < 0.5) {
            return false;
        }
        return brain.forceGoal("share");
    }

    @Override
    public void gainExperience(dev.botalive.core.personality.PersonalityEvolution
                                       .BotExperience experience) {
        if (!(personality instanceof dev.botalive.core.personality.PersonalityImpl impl)) {
            return;
        }
        var result = dev.botalive.core.personality.PersonalityEvolution.apply(impl, experience);
        if (!result.changed()) {
            return;
        }
        repository.savePersonalityTraits(id, impl);
        for (String line : result.announcements()) {
            if (rng.chance(0.6)) {
                chat.say(line);
            }
        }
    }

    /**
     * Sdílené služby předávané botům (jeden parametr místo dlouhého seznamu).
     *
     * @param config      konfigurace
     * @param worldViews  registr pohledů na světy (režim server)
     * @param bridge      most na herní vlákna
     * @param tickEngine  tick engine
     * @param navigation  pathfinding
     * @param repository  repozitář
     * @param phrases     banka frází zvoleného jazyka
     * @param stateMapper překlad block states (jen režim packet, jinak {@code null})
     * @param itemMapper  překlad item ID (jen režim packet, jinak {@code null})
     */
    public record SharedServices(
            BotAliveConfig config,
            WorldViewRegistry worldViews,
            MainThreadBridge bridge,
            BotTickEngine tickEngine,
            NavigationService navigation,
            BotRepository repository,
            dev.botalive.core.chat.PhraseBank phrases,
            dev.botalive.core.world.state.BlockStateMapper stateMapper,
            dev.botalive.core.world.state.ItemMapper itemMapper,
            dev.botalive.core.social.CrimeLog crimeLog
    ) {
    }

    // ======================================================================
    // Lifecycle
    // ======================================================================

    /**
     * Připojí bota k serveru.
     *
     * @param host adresa
     * @param port port
     * @return future dokončený po úspěšném spawnu
     */
    public CompletableFuture<Bot> connect(String host, int port) {
        CompletableFuture<Bot> future = new CompletableFuture<>();
        this.spawnFuture = future;
        state.set(BotLifecycleState.CONNECTING);
        awaitingFirstTeleport = true;
        try {
            connection.connect(host, port);
        } catch (Exception e) {
            state.set(BotLifecycleState.DISCONNECTED);
            future.completeExceptionally(e);
        }
        return future;
    }

    /** Trvale odstraní bota (volá manager). */
    public void markRemoved() {
        removed.set(true);
        tickEngine.stopTicking(this);
        synchronized (tickLock) {
            brain.halt();
            navigator.stop();
        }
        connection.disconnect("Bot odstraněn");
        state.set(BotLifecycleState.REMOVED);
    }

    @Override
    public void disconnect(String reason) {
        tickEngine.stopTicking(this);
        synchronized (tickLock) {
            brain.halt();
            navigator.stop();
        }
        connection.disconnect(reason);
        state.set(BotLifecycleState.DISCONNECTED);
    }

    // ======================================================================
    // NetworkEvents – volané ze síťového vlákna
    // ======================================================================

    @Override
    public void onLogin(int entityId, String worldKey) {
        state.set(BotLifecycleState.CONFIGURING);
        reconnectAttempts.set(0);
        awaitingFirstTeleport = true;
    }

    @Override
    public void onTeleport(TeleportSync teleport) {
        // Aplikace proběhne v ticku; tady jen probudit tick smyčku, pokud ještě neběží.
        if (state.get() == BotLifecycleState.CONFIGURING) {
            startTicking();
        }
    }

    @Override
    public void onDeath(String deathMessage) {
        // Běží na síťovém vlákně – jen thread-safe bookkeeping; halt mozku
        // provede tick vlákno (viz deathHandled v tick()).
        LOG.info("[{}] Zemřel: {}", name, deathMessage);
        stats.addDeath();

        Vec3 pos = physics != null ? physics.position() : Vec3.ZERO;
        String worldName = worldView != null ? worldView.worldName() : "";
        memory.remember(MemoryKind.DEATH, worldName,
                (int) pos.x(), (int) pos.y(), (int) pos.z(), null,
                Map.of("message", deathMessage), 0.9);
        memory.remember(MemoryKind.LOST_ITEMS, worldName,
                (int) pos.x(), (int) pos.y(), (int) pos.z(), null, Map.of(), 0.8);
        gainExperience(dev.botalive.core.personality.PersonalityEvolution.BotExperience.DEATH);
        new BotDiedEvent(this, worldName, (int) pos.x(), (int) pos.y(), (int) pos.z()).callEvent();

        // Humanizovaný respawn: 1.5–6 s „vzpamatovávání".
        long delay = (long) Math.abs(rng.gaussian(2500, 1200)) + 800;
        tickEngine.schedule(() -> {
            if (connection.connected()) {
                actions.respawn();
                if (rng.chance(0.4)) {
                    chat.sayFrom(PhraseCategory.DEATH_REACTIONS, null);
                }
            }
        }, delay);
    }

    @Override
    public void onRespawn(String worldKey, boolean afterDeath) {
        // Respawn paket zaživa = změna dimenze (nether/end portál, plugin
        // teleport mezi světy). Bot si portálový přechod zapamatuje.
        WorldView oldWorld = worldView;
        BotPhysics oldPhysics = physics;
        if (!afterDeath && oldWorld != null && oldPhysics != null
                && !oldWorld.worldName().equals(expectedWorldName(worldKey))) {
            Vec3 pos = oldPhysics.position();
            memory.remember(MemoryKind.PORTAL, oldWorld.worldName(),
                    (int) pos.x(), (int) pos.y(), (int) pos.z(), null,
                    Map.of("to", worldKey), 0.7);
        }
        awaitingFirstTeleport = true;
    }

    @Override
    public void onKnockback(Vec3 impulse) {
        clientState.queueImpulse(impulse);
    }

    @Override
    public void onVehicleMove(Vec3 position, float yaw) {
        vehicle.applyServerVehicleMove(position, yaw);
    }

    @Override
    public void onPlayerChat(UUID sender, String senderName, String content) {
        chat.onMessage(sender, senderName, content);
    }

    @Override
    public void onDisconnected(String reason) {
        tickEngine.stopTicking(this);
        synchronized (tickLock) {
            brain.halt();
            navigator.stop();
        }
        if (removed.get()) {
            return;
        }
        state.set(BotLifecycleState.DISCONNECTED);
        CompletableFuture<Bot> future = spawnFuture;
        if (future != null && !future.isDone()) {
            future.completeExceptionally(new IllegalStateException("Odpojen: " + reason));
        }
        scheduleReconnect();
    }

    /** Automatický reconnect s náhodným zpožděním (žádné synchronní vlny). */
    private void scheduleReconnect() {
        BotAliveConfig.Reconnect reconnect = config.network().reconnect();
        if (!reconnect.enabled()
                || reconnectAttempts.incrementAndGet() > reconnect.maxAttempts()) {
            LOG.warn("[{}] Reconnect vyčerpán, bot zůstává odpojen", name);
            return;
        }
        long delay = (long) rng.range(reconnect.delayMinMs(), reconnect.delayMaxMs());
        LOG.info("[{}] Reconnect za {} ms (pokus {})", name, delay, reconnectAttempts.get());
        tickEngine.schedule(() -> {
            if (!removed.get() && !connection.connected()) {
                connect(resolveHost(), resolvePort());
            }
        }, delay);
    }

    private String resolveHost() {
        return config.network().host();
    }

    private int resolvePort() {
        int port = config.network().port();
        return port > 0 ? port : Bukkit.getPort();
    }

    // ======================================================================
    // Tick smyčka
    // ======================================================================

    /** Spustí periodický tick bota (po prvním teleportu od serveru). */
    private void startTicking() {
        tickEngine.startTicking(this, this::tick, rng.rangeInt(0, 49));
    }

    /** Hlavní tick bota – 20× za sekundu na tick vlákně. */
    private void tick() {
        synchronized (tickLock) {
            tickInner();
        }
    }

    private void tickInner() {
        if (!connection.connected()) {
            return;
        }
        drainTeleports();
        if (physics == null) {
            return; // ještě jsme nedostali první pozici
        }
        drainImpulses();

        // Obsluha smrti na tick vlákně (stav mozku/navigace patří jemu).
        if (clientState.dead() && !deathHandled) {
            deathHandled = true;
            brain.halt();
            navigator.stop();
            combat.disengage();
        } else if (!clientState.dead() && deathHandled) {
            deathHandled = false; // po respawnu
        }

        // Periodický snapshot (server-side, v paketovém režimu klientský) a flush statistik.
        if (++ticksSinceSnapshot >= config.performance().serverSnapshotTicks()) {
            ticksSinceSnapshot = 0;
            if (packetWorlds != null) {
                serverView.offer(PacketPlayerView.capture(clientInventory, itemMapper,
                        clientState, position(), worldView));
            } else {
                serverView.refresh();
            }
        }
        if (++ticksSinceFlush >= 1200) { // ~60 s
            ticksSinceFlush = 0;
            stats.addPlaytime(60);
            stats.flush();
            persistPosition();
        }

        boolean alive = !clientState.dead();
        requestedMove = null;

        if (alive && !paused.get() && state.get() == BotLifecycleState.SPAWNED) {
            brain.tick();
        }

        // Ve vozidle: klientská simulace vozidla nahrazuje pohyb hráče
        // (pozici hráče odvozuje server z vozidla).
        if (vehicle.mounted()) {
            boolean idleInVehicle = !combat.engaged();
            humanizer.tick(position().add(0, 1.62, 0), idleInVehicle && alive);
            vehicle.tick();
            chat.tick();
            return;
        }

        // Zdolávání překážek: když navigace narazí (zazděno, jáma, mezera),
        // bot si cestu odblokuje sám – prokopáním nebo položením bloku.
        if (obstacleTask != null && (!alive || paused.get())) {
            obstacleTask.cancel(this);
            obstacleTask = null;
        }
        MoveInput input;
        if (obstacleTask != null) {
            if (obstacleTask.tick(this)) {
                obstacleTask = null;
                navigator.assistResolved(physics.position());
            }
            input = MoveInput.IDLE; // během zásahu stát
        } else {
            if (alive && !paused.get() && !combat.engaged() && navigator.needsAssist()) {
                obstacleTask = planObstacleRecovery();
                if (obstacleTask == null) {
                    navigator.assistFailed();
                }
            }
            // Pohyb: explicitní požadavek cíle > navigace > stání.
            input = requestedMove != null
                    ? requestedMove
                    : navigator.tick(physics.position(), physics.onGround());
        }
        if (!alive || paused.get()) {
            input = MoveInput.IDLE;
        }

        // Vyhýbání davu: odpuzuj se od blízkých hráčů/botů, ať se boti neslévají
        // na stejné místo. Platí i v boji – tam se ale vyloučí cíl útoku, aby se
        // bot pořád mohl přiblížit k protivníkovi, jen se nehromadil na ostatních
        // útočnících stejného cíle.
        if (alive && !paused.get()) {
            int targetId = combat.engaged() && combat.target() != null
                    ? combat.target().entityId() : -1;
            List<TrackedEntity> crowd = entities.nearby(physics.position(), CrowdAvoidance.radius(),
                    e -> e.isPlayer() && e.entityId() != targetId);
            if (!crowd.isEmpty()) {
                Vec3 steered = CrowdAvoidance.steer(
                        physics.position(), clientState.entityId(), crowd, input.direction());
                if (!steered.equals(input.direction())) {
                    input = new MoveInput(steered, input.sprint(), input.jump(), input.sneak());
                }
            }
        }

        // Přirozený pohled ve směru chůze (pokud cíl neřídí pohled sám).
        if (requestedMove == null && input.direction().horizontalLength() > 1.0E-4) {
            humanizer.lookAlong(input.direction());
        }

        boolean idle = input == MoveInput.IDLE && !combat.engaged();
        humanizer.tick(physics.position().add(0, 1.62, 0), idle && alive);

        physics.step(input);
        movementSender.tick(physics.position(), humanizer.yaw(), humanizer.pitch(),
                physics.onGround(), physics.horizontalCollision(), input);

        // Periodicky: přepočet postupu k životní ambici (levné, cache 2 s).
        if (--ambitionRefreshTicks <= 0) {
            ambitionRefreshTicks = 40;
            refreshAmbition();
        }

        // Periodicky: sebrané brnění nasadit + štít do druhé ruky.
        if (alive && !paused.get() && !combat.engaged() && --armorCheckTicks <= 0) {
            armorCheckTicks = 100 + rng.rangeInt(0, 60);
            var snapshot = serverView.latest();
            if (!inventoryHelper.equipBetterArmor(snapshot,
                    humanizer.yaw(), humanizer.pitch())
                    && snapshot != null
                    && snapshot.offhand() != org.bukkit.Material.SHIELD
                    && inventoryHelper.equipItem(snapshot, org.bukkit.Material.SHIELD)) {
                actions.swapOffhand();
            }
        }

        chat.tick();
        trackDistance();
    }

    /**
     * Naplánuje zásah do terénu pro odblokování cesty.
     *
     * <p>Eskalace jako u hráče: cíl výš → vylámat schod vzhůru (strop nad
     * hlavou, hlava na schodu); rovně/dolů → prorazit štolu 1×2; mezera
     * v podlaze → přemostit blokem. Vše jen s {@code ai.terraforming},
     * s kontrolou tekutin a s nástrojem v ruce.</p>
     *
     * @return task zásahu, nebo {@code null} když zásah nedává smysl
     */
    private dev.botalive.core.tasks.BotTask planObstacleRecovery() {
        if (!config.ai().terraforming() || worldView == null || physics == null) {
            return null;
        }
        BlockPos destination = navigator.destination();
        if (destination == null) {
            return null;
        }
        BlockPos feet = physics.position().toBlockPos();
        int dx = Integer.signum(destination.x() - feet.x());
        int dz = Integer.signum(destination.z() - feet.z());
        boolean preferX = Math.abs(destination.x() - feet.x())
                >= Math.abs(destination.z() - feet.z());
        int sx = preferX ? dx : 0;
        int sz = preferX ? 0 : dz;
        if (sx == 0 && sz == 0) {
            sx = dx != 0 ? dx : 1;
        }
        BlockPos front = feet.offset(sx, 0, sz);
        int dy = Integer.signum(destination.y() - feet.y());

        // Kandidáti na vylámání podle směru (v pořadí důležitosti).
        List<BlockPos> candidates = dy > 0
                ? List.of(feet.up().up(), front.up().up(), front.up())
                : List.of(front.up(), front);
        for (BlockPos block : candidates) {
            if (!worldView.traitsAt(block).solid()) {
                continue;
            }
            if (liquidNear(block)) {
                return null; // za překážkou číhá voda/láva – nechat být
            }
            var material = worldView.materialAt(block);
            inventoryHelper.equipBestTool(serverView.latest(),
                    material != null ? material : org.bukkit.Material.STONE);
            return new dev.botalive.core.tasks.MineBlockTask(block);
        }
        // Vpředu nic pevného → mezera v podlaze: přemostit blokem.
        if (dy <= 0 && worldView.traitsAt(front).passable()
                && worldView.traitsAt(front.down()).passable()
                && !liquidNear(front.down())
                && inventoryHelper.equipBuildingBlock(serverView.latest())) {
            return new dev.botalive.core.tasks.PlaceBlockTask(front.down());
        }
        return null;
    }

    /**
     * Místa špatných vzpomínek v aktuálním světě – bot se učí z vlastních
     * smrtí a poznaných nebezpečí: pathfinding jim zdražuje průchod.
     *
     * @return pozice DEATH/DANGER vzpomínek (max 24)
     */
    private List<BlockPos> dangerMemories() {
        if (worldView == null) {
            return List.of();
        }
        String worldName = worldView.worldName();
        List<BlockPos> result = new java.util.ArrayList<>();
        for (MemoryKind kind : List.of(MemoryKind.DEATH, MemoryKind.DANGER)) {
            for (var record : memory.recall(kind)) {
                if (worldName.equals(record.world())) {
                    result.add(new BlockPos(record.x(), record.y(), record.z()));
                    if (result.size() >= 24) {
                        return result;
                    }
                }
            }
        }
        return result;
    }

    /** Voda/láva v bloku nebo těsném okolí. */
    private boolean liquidNear(BlockPos block) {
        return worldView.traitsAt(block).liquid()
                || worldView.traitsAt(block.up()).liquid()
                || worldView.traitsAt(block.down()).liquid()
                || worldView.traitsAt(block.offset(1, 0, 0)).liquid()
                || worldView.traitsAt(block.offset(-1, 0, 0)).liquid()
                || worldView.traitsAt(block.offset(0, 0, 1)).liquid()
                || worldView.traitsAt(block.offset(0, 0, -1)).liquid();
    }

    /** Aplikuje teleporty od serveru na fyziku. */
    private void drainTeleports() {
        TeleportSync teleport;
        while ((teleport = clientState.pollTeleport()) != null) {
            Vec3 current = physics != null ? physics.position() : Vec3.ZERO;
            List<PositionElement> relatives = teleport.relatives();
            double x = relatives.contains(PositionElement.X)
                    ? current.x() + teleport.position().x() : teleport.position().x();
            double y = relatives.contains(PositionElement.Y)
                    ? current.y() + teleport.position().y() : teleport.position().y();
            double z = relatives.contains(PositionElement.Z)
                    ? current.z() + teleport.position().z() : teleport.position().z();
            Vec3 target = new Vec3(x, y, z);

            boolean worldChanged = physics == null || worldView == null
                    || !worldView.worldName().equals(expectedWorldName(clientState.worldKey()));
            if (worldChanged) {
                switchWorld(clientState.worldKey(), target);
            } else {
                physics.teleport(target);
            }
            humanizer.snapTo(teleport.yaw(), teleport.pitch());
            movementSender.resetTo(target, teleport.yaw(), teleport.pitch());
            lastDistancePos = target;

            // Velký skok (admin /tp, plugin, portál): rozpracovaná cesta a boj
            // už neplatí – cíle si naplánují nové podle nové pozice.
            if (worldChanged || target.distanceSquared(current) > 8 * 8) {
                navigator.stop();
                combat.disengage();
                vehicle.stopCruise();
                if (worldChanged) {
                    worldView.prefetch(target.toBlockPos(), 2);
                }
            }

            if (awaitingFirstTeleport) {
                awaitingFirstTeleport = false;
                onSpawnComplete(target);
            }
        }
    }

    /** Aplikuje knockback impulzy od serveru (absolutní rychlost). */
    private void drainImpulses() {
        Vec3 impulse;
        while ((impulse = clientState.pollImpulse()) != null) {
            physics.setVelocity(impulse);
        }
    }

    /** První pozice po loginu/respawnu – dokončení spawn sekvence. */
    private void onSpawnComplete(Vec3 position) {
        connection.send(org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound
                .ServerboundPlayerLoadedPacket.INSTANCE);
        state.set(BotLifecycleState.SPAWNED);
        LOG.info("[{}] Naspawnován na {} v {}", name, position.toBlockPos(),
                worldView != null ? worldView.worldName() : "?");

        // Životní ambice: vybrat jednou podle povahy a zapamatovat.
        if (memory.recall(MemoryKind.AMBITION).isEmpty()) {
            var picked = dev.botalive.core.ai.Ambition.pick(personality);
            memory.remember(MemoryKind.AMBITION,
                    worldView != null ? worldView.worldName() : "", 0, 0, 0, null,
                    Map.of("type", picked.name()), 1.0);
            this.ambition = picked;
        }

        // Domov: pokud bot žádný nemá, je jím první spawn.
        if (worldView != null && memory.recall(MemoryKind.HOME).isEmpty()) {
            BlockPos pos = position.toBlockPos();
            memory.remember(MemoryKind.HOME, worldView.worldName(),
                    pos.x(), pos.y(), pos.z(), null, Map.of("type", "spawn"), 0.6);
        }

        CompletableFuture<Bot> future = spawnFuture;
        if (future != null && !future.isDone()) {
            future.complete(this);
        }
        new BotSpawnedEvent(this).callEvent();
    }

    /** Přepnutí světa (login, respawn, portál). */
    private void switchWorld(String worldKey, Vec3 position) {
        if (packetWorlds != null) {
            // Klientský world model – geometrie z chunk paketů, žádný Bukkit svět.
            this.worldView = packetWorlds.switchTo(worldKey);
        } else {
            String worldName = resolveWorldName(worldKey);
            try {
                this.worldView = worldViews.view(worldName);
            } catch (IllegalArgumentException e) {
                LOG.error("[{}] Neznámý svět '{}' (klíč {})", name, worldName, worldKey);
                return;
            }
        }
        this.physics = new BotPhysics(worldView, position);
        navigator.world(worldView);
        entities.clear();
        if (obstacleTask != null) {
            obstacleTask.cancel(this);
            obstacleTask = null;
        }
        worldView.prefetch(position.toBlockPos(), 2);
    }

    /** Očekávaný název světa pro daný protokolový klíč (dle režimu world modelu). */
    private String expectedWorldName(String worldKey) {
        return packetWorlds != null ? worldKey : resolveWorldName(worldKey);
    }

    /** Mapování protokolového klíče světa na Bukkit název světa. */
    private String resolveWorldName(String worldKey) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getKey().asString().equals(worldKey)) {
                return world.getName();
            }
        }
        return Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().getFirst().getName();
    }

    /** Průběžné počítání uražené vzdálenosti do statistik. */
    private void trackDistance() {
        Vec3 pos = physics.position();
        if (lastDistancePos != null) {
            double moved = pos.distance(lastDistancePos);
            if (moved > 0.01 && moved < 10) {
                stats.addDistance(moved);
            }
        }
        lastDistancePos = pos;
    }

    /** Uloží aktuální pozici bota do DB (obnova po restartu). */
    private void persistPosition() {
        if (physics == null || worldView == null) {
            return;
        }
        Vec3 pos = physics.position();
        repository.upsertBot(id, name, worldView.worldName(), pos.x(), pos.y(), pos.z());
    }

    /** Doručení zprávy z chat enginu – přes Bukkit event do protokolu. */
    private void deliverChat(String message) {
        BotChatEvent event = new BotChatEvent(this, message);
        if (event.callEvent()) {
            actions.chat(event.message());
            stats.addMessage();
        }
    }

    // ======================================================================
    // API (dev.botalive.api.bot.Bot)
    // ======================================================================

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public BotLifecycleState state() {
        return state.get();
    }

    @Override
    public BotSnapshot snapshot() {
        Vec3 pos = physics != null ? physics.position() : Vec3.ZERO;
        return new BotSnapshot(id, name, state.get(),
                worldView != null ? worldView.worldName() : null,
                pos.x(), pos.y(), pos.z(), humanizer.yaw(), humanizer.pitch(),
                clientState.health(), clientState.food(),
                brain.currentGoalId(), role, connection.connected());
    }

    @Override
    public Personality personality() {
        return personality;
    }

    @Override
    public dev.botalive.api.role.BotRole role() {
        return role;
    }

    @Override
    public void role(dev.botalive.api.role.BotRole newRole) {
        this.role = newRole == null ? dev.botalive.api.role.BotRole.NONE : newRole;
        repository.saveRole(id, this.role.name());
    }

    @Override
    public BotMemory memory() {
        return memory;
    }

    @Override
    public BotWallet wallet() {
        return wallet;
    }

    @Override
    public void pause() {
        if (paused.compareAndSet(false, true)) {
            synchronized (tickLock) {
                brain.halt();
                navigator.stop();
                combat.disengage();
            }
            if (state.get() == BotLifecycleState.SPAWNED) {
                state.set(BotLifecycleState.PAUSED);
            }
        }
    }

    @Override
    public void resume() {
        if (paused.compareAndSet(true, false)
                && state.get() == BotLifecycleState.PAUSED) {
            state.set(BotLifecycleState.SPAWNED);
        }
    }

    @Override
    public boolean paused() {
        return paused.get();
    }

    @Override
    public boolean forceGoal(String goalId) {
        return brain.forceGoal(goalId);
    }

    /**
     * @return aktuální utility hodnoty všech cílů (pro {@code /botalive goal})
     */
    public Map<String, Double> utilitySnapshot() {
        return brain.utilitySnapshot();
    }

    @Override
    public void say(String message) {
        chat.say(message);
    }

    @Override
    public CompletableFuture<Boolean> teleport(Location location) {
        org.bukkit.entity.Player player = Bukkit.getPlayer(id);
        if (player == null || location.getWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (!config.worlds().allowed(location.getWorld().getName())) {
            return CompletableFuture.completedFuture(false);
        }
        // Teleport přes vlákno entity (Folia-safe); klient se resynchronizuje
        // position/respawn paketem od serveru (viz drainTeleports).
        return bridge.callForEntity(player, () -> player.teleportAsync(location))
                .thenCompose(future -> future == null
                        ? CompletableFuture.completedFuture(false)
                        : future)
                .exceptionally(t -> {
                    LOG.warn("[{}] Teleport selhal: {}", name, t.toString());
                    return false;
                });
    }

    @Override
    public CompletableFuture<Boolean> teleportToPlayer(UUID playerId) {
        org.bukkit.entity.Player target = Bukkit.getPlayer(playerId);
        if (target == null) {
            return CompletableFuture.completedFuture(false);
        }
        // Pozice hráče se čte na jeho vlákně; pak standardní bot-teleport.
        return bridge.callForEntity(target, target::getLocation)
                .thenCompose(location -> location == null
                        ? CompletableFuture.completedFuture(false)
                        : teleport(location))
                .exceptionally(t -> {
                    LOG.warn("[{}] Teleport k hráči selhal: {}", name, t.toString());
                    return false;
                });
    }

    @Override
    public CompletableFuture<Boolean> teleportPlayerToBot(UUID playerId) {
        org.bukkit.entity.Player traveler = Bukkit.getPlayer(playerId);
        org.bukkit.entity.Player botPlayer = Bukkit.getPlayer(id);
        if (traveler == null || botPlayer == null) {
            return CompletableFuture.completedFuture(false);
        }
        // Pozice bota na vlákně jeho entity → teleport hráče na jeho vlákně.
        return bridge.callForEntity(botPlayer, botPlayer::getLocation)
                .thenCompose(location -> {
                    if (location == null) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return bridge.callForEntity(traveler, () -> traveler.teleportAsync(location))
                            .thenCompose(future -> future == null
                                    ? CompletableFuture.completedFuture(false)
                                    : future);
                })
                .exceptionally(t -> {
                    LOG.warn("[{}] Teleport hráče k botovi selhal: {}", name, t.toString());
                    return false;
                });
    }

    // ======================================================================
    // BotContext (interní přístup pro cíle)
    // ======================================================================

    @Override
    public Bot bot() {
        return this;
    }

    @Override
    public BotClientState clientState() {
        return clientState;
    }

    @Override
    public EntityTracker entities() {
        return entities;
    }

    @Override
    public Navigator navigator() {
        return navigator;
    }

    @Override
    public BotActions actions() {
        return actions;
    }

    @Override
    public Humanizer humanizer() {
        return humanizer;
    }

    @Override
    public ServerSideView serverView() {
        return serverView;
    }

    @Override
    public InventoryHelper inventory() {
        return inventoryHelper;
    }

    @Override
    public ClientInventory clientInventory() {
        return clientInventory;
    }

    @Override
    public dev.botalive.core.container.ContainerTracker containers() {
        return containerTracker;
    }

    @Override
    public dev.botalive.core.container.ContainerClicker clicker() {
        return containerClicker;
    }

    @Override
    public dev.botalive.core.world.state.ItemMapper itemMapper() {
        return itemMapper;
    }

    @Override
    public WorldView worldView() {
        return worldView;
    }

    @Override
    public BotRandom rng() {
        return rng;
    }

    @Override
    public ChatEngine chat() {
        return chat;
    }

    @Override
    public CombatController combat() {
        return combat;
    }

    @Override
    public BotStats stats() {
        return stats;
    }

    @Override
    public BotAliveConfig config() {
        return config;
    }

    @Override
    public MainThreadBridge bridge() {
        return bridge;
    }

    @Override
    public VehicleController vehicle() {
        return vehicle;
    }

    @Override
    public Vec3 position() {
        // Při plavbě/jízdě je pozice bota odvozená z vozidla.
        Vec3 vehiclePos = vehicle.vehiclePosition();
        if (vehiclePos != null && vehicle.mounted()) {
            return vehiclePos;
        }
        BotPhysics currentPhysics = physics;
        return currentPhysics != null ? currentPhysics.position() : Vec3.ZERO;
    }

    @Override
    public boolean onGround() {
        BotPhysics currentPhysics = physics;
        return currentPhysics != null && currentPhysics.onGround();
    }

    @Override
    public long worldTime() {
        ServerSideView.Snapshot snapshot = serverView.latest();
        return snapshot != null ? snapshot.worldTime() : -1;
    }

    @Override
    public void requestMove(MoveInput input) {
        this.requestedMove = input;
    }

    @Override
    public CompletableFuture<Integer> estimateBreakTicks(BlockPos pos) {
        if (packetWorlds != null) {
            return CompletableFuture.completedFuture(estimateBreakTicksClientSide(pos));
        }
        org.bukkit.entity.Player player = Bukkit.getPlayer(id);
        WorldView view = worldView;
        if (player == null || view == null) {
            return CompletableFuture.completedFuture(40);
        }
        World world = Bukkit.getWorld(view.worldName());
        if (world == null) {
            return CompletableFuture.completedFuture(40);
        }
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        return bridge.callAt(location, () -> {
                    float speed = world.getBlockAt(pos.x(), pos.y(), pos.z()).getBreakSpeed(player);
                    if (speed <= 0) {
                        return 6000; // prakticky nerozbitelné
                    }
                    if (speed >= 1) {
                        return 1;    // instant-break
                    }
                    return (int) Math.ceil(1.0f / speed);
                })
                .exceptionally(t -> 40);
    }

    /**
     * Klientský odhad doby kopání (paketový režim – server-side data nejsou).
     * Tvrdost dává {@code Material} (statická vanilla data hostitele), třídu
     * nástroje {@code InventoryHelper}; enchanty a efekty odhad ignoruje –
     * {@code MineBlockTask} beztak ověřuje skutečné zmizení bloku.
     */
    private int estimateBreakTicksClientSide(BlockPos pos) {
        WorldView view = worldView;
        org.bukkit.Material block = view == null ? null : view.materialAt(pos);
        if (block == null || block.isAir()) {
            return 40;
        }
        org.bukkit.Material held = null;
        if (itemMapper != null) {
            var stack = clientInventory.hotbar(clientState.heldSlot());
            held = stack == null ? null : itemMapper.materialOf(stack.getId());
        }
        InventoryHelper.ToolType required = InventoryHelper.toolFor(block);
        boolean correctTool = held != null && required != InventoryHelper.ToolType.NONE
                && InventoryHelper.isTool(held, required);
        int tier = held == null ? 0 : InventoryHelper.toolTier(held);
        // „Sklizeň rukou": jen krumpáčové bloky (a pavučina) vyžadují nástroj.
        boolean harvestable = correctTool
                || (required != InventoryHelper.ToolType.PICKAXE
                        && required != InventoryHelper.ToolType.SWORD);
        return dev.botalive.core.world.BreakTimeEstimator.estimateTicks(
                block.getHardness(), correctTool, tier, harvestable);
    }
}
