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
import dev.botalive.core.chat.PhraseBank;
import dev.botalive.core.combat.CombatController;
import dev.botalive.core.combat.CombatDifficulty;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.entity.EntityTracker;
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
public final class BotImpl implements Bot, BotContext, NetworkEvents {

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
    private final Brain brain;

    // --- Mutable stav (tick vlákno / volatile) ---------------------------------
    private final AtomicReference<BotLifecycleState> state =
            new AtomicReference<>(BotLifecycleState.CREATED);
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean removed = new AtomicBoolean();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();

    private volatile WorldView worldView;
    private volatile BotPhysics physics;
    private volatile CompletableFuture<Bot> spawnFuture;

    /** Pohyb vyžádaný cílem pro aktuální tick (přebíjí navigátor). */
    private MoveInput requestedMove;

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

        this.rng = new BotRandom(id.getLeastSignificantBits() ^ System.nanoTime());
        this.connection = new BotConnection(name, id, config.network(),
                new BotSessionListener(name, clientState, entities, clientInventory, this));
        this.actions = new BotActions(connection, clientState);
        this.movementSender = new MovementSender(connection, clientState);
        this.humanizer = new Humanizer(rng, personality);
        this.navigator = new Navigator(services.navigation(), actions, rng, personality);
        this.inventoryHelper = new InventoryHelper(actions);
        this.combat = new CombatController(actions, humanizer, rng, personality, config.combat(),
                CombatDifficulty.fromConfig(config.ai().difficulty()), inventoryHelper);
        this.vehicle = new VehicleController(connection, clientState);
        this.serverView = new ServerSideView(id, bridge);
        this.chat = new ChatEngine(name, personality, rng, config.chat(), this::deliverChat);
        this.brain = new Brain(this, goalFactory.apply(this),
                config.ai().decisionIntervalTicks(), config.ai().goalHysteresis(), rng);
    }

    /**
     * Sdílené služby předávané botům (jeden parametr místo dlouhého seznamu).
     *
     * @param config     konfigurace
     * @param worldViews registr pohledů na světy
     * @param bridge     most na herní vlákna
     * @param tickEngine tick engine
     * @param navigation pathfinding
     * @param repository repozitář
     */
    public record SharedServices(
            BotAliveConfig config,
            WorldViewRegistry worldViews,
            MainThreadBridge bridge,
            BotTickEngine tickEngine,
            NavigationService navigation,
            BotRepository repository
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
        new BotDiedEvent(this, worldName, (int) pos.x(), (int) pos.y(), (int) pos.z()).callEvent();

        // Humanizovaný respawn: 1.5–6 s „vzpamatovávání".
        long delay = (long) Math.abs(rng.gaussian(2500, 1200)) + 800;
        tickEngine.schedule(() -> {
            if (connection.connected()) {
                actions.respawn();
                if (rng.chance(0.4)) {
                    chat.sayFrom(PhraseBank.DEATH_REACTIONS, null);
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
                && !oldWorld.worldName().equals(resolveWorldName(worldKey))) {
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

        // Periodický server-side snapshot a flush statistik.
        if (++ticksSinceSnapshot >= config.performance().serverSnapshotTicks()) {
            ticksSinceSnapshot = 0;
            serverView.refresh();
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

        // Pohyb: explicitní požadavek cíle > navigace > stání.
        MoveInput input = requestedMove != null
                ? requestedMove
                : navigator.tick(physics.position(), physics.onGround());
        if (!alive || paused.get()) {
            input = MoveInput.IDLE;
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

        chat.tick();
        trackDistance();
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
                    || !worldView.worldName().equals(resolveWorldName(clientState.worldKey()));
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
        String worldName = resolveWorldName(worldKey);
        try {
            this.worldView = worldViews.view(worldName);
        } catch (IllegalArgumentException e) {
            LOG.error("[{}] Neznámý svět '{}' (klíč {})", name, worldName, worldKey);
            return;
        }
        this.physics = new BotPhysics(worldView, position);
        navigator.world(worldView);
        entities.clear();
        worldView.prefetch(position.toBlockPos(), 2);
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
                brain.currentGoalId(), connection.connected());
    }

    @Override
    public Personality personality() {
        return personality;
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
}
