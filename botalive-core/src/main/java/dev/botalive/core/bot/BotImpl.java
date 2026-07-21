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
import dev.botalive.api.memory.MemoryRecord;
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
    private final dev.botalive.core.container.ContainerTracker containerTracker =
            new dev.botalive.core.container.ContainerTracker();
    private final dev.botalive.core.container.ContainerClicker containerClicker;
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

    // --- Mutable stav (tick vlákno / volatile) ---------------------------------
    private final AtomicReference<BotLifecycleState> state =
            new AtomicReference<>(BotLifecycleState.CREATED);
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean removed = new AtomicBoolean();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();

    private volatile WorldView worldView;
    private volatile BotPhysics physics;
    private volatile CompletableFuture<Bot> spawnFuture;

    /**
     * Profese bota jako id (vestavěná nebo cizí) – násobí utility souvisejících
     * cílů přes registr rolí. {@code "none"} = univerzál.
     */
    private volatile String roleId = "none";

    /** Veřejné akční rozhraní bota pro cizí AI cíle (lazy, sdílená instance). */
    private volatile dev.botalive.api.bot.BotControl control;

    /** Pohyb vyžádaný cílem pro aktuální tick (přebíjí navigátor). */
    private MoveInput requestedMove;
    /** Probíhající zásah do terénu při zdolávání překážky (kopání/pokládání). */
    private dev.botalive.core.tasks.BotTask obstacleTask;
    /** Běžící {@code obstacleTask} je zásah z plánu cesty (ne reaktivní assist). */
    private boolean actionTaskRunning;

    /** Adresná prosba o sdílení z chatu; vyzvedne si ji ShareGoal. */
    private volatile dev.botalive.core.ai.ShareRequest pendingShare;

    /** Kolik ticků v kuse je bot zazděný (stabilizace nouzového vyproštění). */
    private int buriedTicks;
    /** Odpočet, než bot znovu zváží loď (po přejezdu/neúspěchu se nezkouší hned). */
    private int boatCheckCooldown;
    /** Odstup mezi pokusy o přejezd lávy na striderovi (viz boatCheckCooldown). */
    private int striderCheckCooldown;
    /** Odpočet periodické kontroly brnění v hotbaru. */
    private int armorCheckTicks = 60;
    /** Odpočet pasivního všímání si portálů do Endu v okolí. */
    private int portalScanTicks = 200;
    /** Životní ambice (cache; zdroj pravdy je paměť AMBITION). */
    private volatile dev.botalive.core.ai.Ambition ambition;
    private volatile dev.botalive.core.ai.Ambition.Progress ambitionProgress;
    private int ambitionRefreshTicks;

    /** Čeká se na první teleport po loginu/respawnu (pošle se PlayerLoaded). */
    private volatile boolean awaitingFirstTeleport = true;

    /**
     * Název světa, ze kterého bot právě prošel portálem (nastavuje síťové
     * vlákno při živém respawnu se změnou světa). První teleport v novém
     * světě je pozice cílového portálu – zapíše se jako PORTAL vzpomínka,
     * aby bot uměl najít cestu zpátky.
     */
    private volatile String portalArrivedFrom;

    /** Smrt už byla obsloužena na tick vlákně (halt mozku, stop navigace). */
    private boolean deathHandled;

    /** Zámek tick smyčky – chrání stav confinovaný na tick vlákno před
     *  lifecycle zásahy z jiných vláken (disconnect, pause, remove). */
    private final Object tickLock = new Object();

    /** Jak dlouho po splnění ambice se vybírá další (radost si užít). */
    private static final long AMBITION_AFTERGLOW_MS = 10 * 60_000L;

    private int ticksSinceSnapshot;
    private int ticksSinceFlush;
    private int ticksSinceCohesion;
    private int ticksSinceWelcome;
    private int ticksSinceDiplomacy;
    private int ticksSinceEmployment;
    private long ambitionCompletedAt;
    private Vec3 lastDistancePos;

    /** Kdy byl který hráč naposledy přivítán ve vesnici (per-bot paměť). */
    private final Map<UUID, Long> welcomedPlayers =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Jak dlouho se stejný hráč znovu nevítá (ms). */
    private static final long WELCOME_COOLDOWN_MS = 20 * 60_000L;

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
        this.connection = new BotConnection(name, id, config.network(), config.gateway(),
                services.authority(),
                new BotSessionListener(name, clientState, entities,
                        containerTracker, this));
        this.containerClicker = new dev.botalive.core.container.ContainerClicker(
                connection, containerTracker);
        this.actions = new BotActions(connection, clientState);
        this.movementSender = new MovementSender(connection, clientState);
        this.humanizer = new Humanizer(rng, personality);
        // Navigátor ze sítě potřebuje jedinou akci – klik na dveře v cestě.
        this.navigator = new Navigator(services.navigation(),
                door -> actions.useItemOn(door,
                        org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction.NORTH),
                rng, personality);
        this.navigator.terraforming(config.ai().terraforming());
        this.navigator.placeBudget(this::buildingBlockBudget);
        this.navigator.ladderBudget(this::ladderBudget);
        this.navigator.dangerSupplier(this::dangerMemories);
        this.inventoryHelper = new InventoryHelper(actions);
        this.inventoryHelper.puller(this::pullToHotbar);
        this.combat = new CombatController(actions, humanizer, rng, personality, config.combat(),
                CombatDifficulty.fromConfig(config.ai().difficulty()), inventoryHelper);
        this.combat.navigation(navigator);
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

    @Override
    public dev.botalive.core.social.SocialGraph socialGraph() {
        return services.socialGraph();
    }

    @Override
    public boolean raining() {
        return clientState.raining();
    }

    @Override
    public boolean thundering() {
        return clientState.thundering();
    }

    @Override
    public dev.botalive.core.settlement.SettlementService settlements() {
        return services.settlements();
    }

    @Override
    public dev.botalive.core.settlement.SocialView settlementView() {
        java.util.Map<UUID, Double> friends = new java.util.HashMap<>();
        java.util.Map<UUID, Double> enemies = new java.util.HashMap<>();
        java.util.Map<UUID, Long> enemyUpdatedAt = new java.util.HashMap<>();
        for (MemoryRecord record : memory.recall(MemoryKind.FRIEND)) {
            if (record.subject() != null) {
                friends.merge(record.subject(), record.importance(), Math::max);
            }
        }
        for (MemoryRecord record : memory.recall(MemoryKind.ENEMY)) {
            if (record.subject() != null) {
                enemies.merge(record.subject(), record.importance(), Math::max);
                enemyUpdatedAt.merge(record.subject(), record.updatedAt(), Math::max);
            }
        }
        dev.botalive.core.util.BlockPos house = null;
        for (MemoryRecord record : memory.recall(MemoryKind.HOME)) {
            if ("house".equals(record.data().get("type"))) {
                house = new dev.botalive.core.util.BlockPos(record.x(), record.y(), record.z());
                break;
            }
        }
        var world = worldView();
        return new dev.botalive.core.settlement.SocialView(id, name,
                world == null ? null : world.worldName(),
                position().toBlockPos(),
                personality.trait(dev.botalive.api.personality.Trait.SOCIABILITY),
                personality.trait(dev.botalive.api.personality.Trait.PATIENCE),
                house, friends, enemies, enemyUpdatedAt);
    }

    /**
     * Sousedská úvaha: jednou za ~30–45 s zváží roztržky, stěhování za
     * kamarády a usazování samotářů. Změna členství znamená zapomenout
     * domov (bot si postaví nový na parcele) a okomentovat to v chatu.
     */
    private void tickSettlementCohesion() {
        if (!config.settlement().enabled() || services.settlements() == null) {
            return;
        }
        // Cíl uprostřed práce (stavba domu) smí stěhování odložit.
        if (brain.currentGoalBlocksRelocation()) {
            return;
        }
        var view = settlementView();
        if (view.world() == null) {
            return;
        }
        services.settlements().checkCohesion(view).ifPresent(action -> {
            if (action.rebuild()) {
                // Staré stavby (dům, postel, přístřešek) zůstávají v opuštěné
                // vesnici – zapomenout; kotva spawnu je nouzové útočiště a zůstává.
                memory.forgetIf(MemoryKind.HOME,
                        r -> !"spawn".equals(r.data().get("type")));
            }
            dev.botalive.core.settlement.SettlementAnnouncer.say(chat, action);
            if (action.type() == dev.botalive.core.settlement.SettlementService
                    .CohesionAction.Type.JOIN_NEARBY) {
                maybeAdoptRole();
            }
        });
    }

    /**
     * Univerzál vstupující do sídla převezme řemeslo, které tam nikdo nedělá
     * (fáze C růstové roadmapy) – vesnice si řemeslníky vychovává, nevnucuje:
     * zavedeným členům se role nemění, jen nový bez vyhraněného zaměření
     * dostane při vstupu nabídku, kde je potřeba.
     */
    private void maybeAdoptRole() {
        if (!"none".equals(roleId)) {
            return; // zaměřený bot (i cizí rolí) si řemeslo nevnucuje
        }
        services.settlements().settlementIdOf(id).ifPresent(settlementId ->
                services.settlements().missingCoreRole(settlementId).ifPresent(needed -> {
                    role(needed);
                    chat.sayFrom(dev.botalive.core.chat.PhraseCategory
                            .SETTLEMENT_ROLE_TAKEN, needed.displayName());
                }));
    }

    /**
     * Vesnice vítá hráče: člen poblíž návsi přivítá procházejícího
     * <b>skutečného</b> hráče jménem vesnice; kamarádovi-hráči nabídne,
     * že ho provede k trhu. Čistě chat – guvernér (rozestupy, dav, sborový
     * dedupe) drží hlasitost, per-hráč cooldown brání papouškování.
     */
    private void tickVillageWelcome() {
        if (!config.settlement().enabled() || !config.chat().enabled()) {
            return;
        }
        var settlements = services.settlements();
        var graph = services.socialGraph();
        if (settlements == null || graph == null || worldView == null || physics == null) {
            return;
        }
        var own = settlements.settlementOf(id).orElse(null);
        if (own == null || !own.world().equals(worldView.worldName())) {
            return;
        }
        // Vítá se jen u návsi – bot na výpravě za rudou nezdraví jménem vesnice.
        Vec3 pos = physics.position();
        int villageRadius = config.settlement().plotSpacing() * 3;
        BlockPos center = own.center();
        double dx = pos.x() - center.x();
        double dz = pos.z() - center.z();
        if (dx * dx + dz * dz > (double) villageRadius * villageRadius) {
            return;
        }
        long now = System.currentTimeMillis();
        for (TrackedEntity entity : entities.nearby(pos, 12, TrackedEntity::isPlayer)) {
            UUID uuid = entity.uuid();
            if (uuid == null || graph.isBot(uuid)) {
                continue; // sousedy z vesnice nikdo nevítá jak turisty
            }
            Long last = welcomedPlayers.get(uuid);
            if (last != null && now - last < WELCOME_COOLDOWN_MS) {
                continue;
            }
            welcomedPlayers.put(uuid, now);
            if (welcomedPlayers.size() > 64) {
                welcomedPlayers.values().removeIf(at -> now - at > WELCOME_COOLDOWN_MS);
            }
            var about = memory.recallAbout(uuid);
            boolean enemy = about.stream().anyMatch(r -> r.kind() == MemoryKind.ENEMY
                    && r.importance() >= config.settlement().grudgeThreshold());
            if (enemy || !rng.chance(0.6)) {
                continue; // nepřítele nevítáme; a nevítá každý bot pokaždé
            }
            boolean friend = about.stream().anyMatch(r -> r.kind() == MemoryKind.FRIEND
                    && r.importance() >= dev.botalive.core.pvp.PvpCoordinator.ALLY_THRESHOLD);
            org.bukkit.entity.Player bukkit = Bukkit.getPlayer(uuid);
            String playerName = bukkit != null ? bukkit.getName() : null;
            if (friend && playerName != null) {
                chat.sayFrom(PhraseCategory.VILLAGE_TOUR, playerName);
            } else {
                chat.sayFrom(PhraseCategory.VILLAGE_WELCOME, own.name());
            }
            break; // jedno přivítání na průchod
        }
    }

    /**
     * Přitáhne item z hlavního inventáře do hotbaru (SWAP klik ve vlastním
     * okně inventáře – funguje v server i packet režimu, je to čistý protokol).
     *
     * @param predicate co přitáhnout
     * @return hotbar index, kam item dorazil, nebo -1
     */
    private int pullToHotbar(java.util.function.Predicate<org.bukkit.Material> predicate) {
        var snapshot = serverView.latest();
        if (snapshot == null) {
            return -1;
        }
        int source = InventoryHelper.findBestInMain(snapshot, predicate);
        if (source < 0) {
            return -1;
        }
        int target = InventoryHelper.chooseHotbarDumpSlot(snapshot);
        // Sloty okna inventáře: 9–35 hlavní inventář (shodné číslování s Bukkitem).
        containerClicker.moveToHotbar(0, 9 + source, target);
        serverView.refresh(); // snapshot co nejdřív dohnat realitu
        return target;
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
        // Revalidace: ambici uloženou za jiné konfigurace (vypnuté výpravy)
        // vyměnit hned – jinak by navěky blokovala výběr dalšího snu.
        if (!ambitionAllowed(ambition)) {
            var state = ambitionState();
            for (var candidate : dev.botalive.core.ai.Ambition.ranked(personality)) {
                if (candidate == ambition || !ambitionAllowed(candidate)
                        || candidate.progress(state).complete()) {
                    continue;
                }
                memory.forget(MemoryKind.AMBITION);
                memory.remember(MemoryKind.AMBITION,
                        worldView != null ? worldView.worldName() : "", 0, 0, 0,
                        null, Map.of("type", candidate.name()), 1.0);
                ambition = candidate;
                ambitionProgress = null;
                return;
            }
            return; // žádná povolená alternativa – nechat a zkusit příště
        }
        var state = ambitionState();
        ambitionProgress = ambition.progress(state);

        // Splněný sen chvíli hřeje – a pak si člověk najde další. Nová ambice
        // se volí podle aktuální (vyvinuté) povahy; hotové cíle se přeskakují.
        if (ambitionProgress.complete()) {
            if (ambitionCompletedAt == 0) {
                ambitionCompletedAt = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - ambitionCompletedAt
                    > AMBITION_AFTERGLOW_MS) {
                for (var candidate : dev.botalive.core.ai.Ambition.ranked(personality)) {
                    if (candidate == ambition || !ambitionAllowed(candidate)
                            || candidate.progress(state).complete()) {
                        continue;
                    }
                    memory.forget(MemoryKind.AMBITION);
                    memory.remember(MemoryKind.AMBITION,
                            worldView != null ? worldView.worldName() : "", 0, 0, 0,
                            null, Map.of("type", candidate.name()), 1.0);
                    ambition = candidate;
                    ambitionProgress = null;
                    chat.sayFrom(PhraseCategory.AMBITION_NEW, candidate.label());
                    break;
                }
                // Všechno splněné → žije z renty; zkusí to zas za chvíli
                // (stav se může změnit – vyloupená truhla, ztracený dům).
                ambitionCompletedAt = System.currentTimeMillis();
            }
        } else {
            ambitionCompletedAt = 0;
        }
    }

    /** Stav bota pro výpočet postupu ambicí (inventář, dům, peníze, End). */
    private dev.botalive.core.ai.Ambition.State ambitionState() {
        var snapshot = serverView.latest();
        var needs = dev.botalive.core.ai.BotNeeds.assess(snapshot);
        boolean hasHouse = memory.recall(MemoryKind.HOME).stream()
                .anyMatch(r -> "house".equals(r.data().get("type")));
        boolean hasBed = snapshot != null
                && snapshot.hasItem(m -> m.name().endsWith("_BED"));
        var readiness = dev.botalive.core.ai.EndReadiness.assess(snapshot);
        return new dev.botalive.core.ai.Ambition.State(needs, hasHouse, hasBed,
                wallet.balance(),
                readiness.swordTier() >= 4 && readiness.armorPieces() >= 3,
                readiness.hasBow(),
                dev.botalive.core.ai.EndKnowledge.knowsEndPortal(
                        memory.recall(MemoryKind.PORTAL)),
                dev.botalive.core.ai.EndKnowledge.dragonSlain(
                        memory.recall(MemoryKind.TROPHY)));
    }

    /**
     * Přišel bot ze světa, který je Endem? Heuristika jména + vlastní
     * vzpomínky s autoritativní anotací dimenze (custom jména End světů).
     */
    private boolean cameFromEnd(String fromWorld) {
        if (dev.botalive.core.world.WorldDimension.fromWorldKey(fromWorld)
                == dev.botalive.core.world.WorldDimension.END) {
            return true;
        }
        return memory.recall(MemoryKind.PORTAL).stream()
                .anyMatch(r -> fromWorld.equals(r.world())
                        && "end".equals(r.data().get("dim")));
    }

    /**
     * Oživí vlastní průchodové vzpomínky vedoucí do daného End světa
     * (re-remember = merge bumpne {@code updatedAt} k času návratu, kotva
     * cooldownu výprav) a doplní jim anotaci {@code dim=end}.
     */
    private void touchOwnEndPassages(String endWorld) {
        annotateEndPassages(endWorld, endWorld);
    }

    /** Doplní {@code dim=end} vlastním průchodům s {@code to=endWorld}. */
    private void annotateEndPassages(String endWorld, String toValue) {
        for (MemoryRecord record : memory.recall(MemoryKind.PORTAL)) {
            if (!endWorld.equals(record.data().get("to"))
                    || "gossip".equals(record.data().get("via"))) {
                continue;
            }
            memory.remember(MemoryKind.PORTAL, record.world(),
                    record.x(), record.y(), record.z(), record.subject(),
                    Map.of("to", toValue, "dim", "end"), record.importance());
        }
    }

    /** Smí bot tuhle ambici mít? (výpravové sny jen se zapnutými výpravami) */
    private boolean ambitionAllowed(dev.botalive.core.ai.Ambition candidate) {
        if (candidate == dev.botalive.core.ai.Ambition.DRAGON_SLAYER) {
            return config.end().enabled();
        }
        if (candidate == dev.botalive.core.ai.Ambition.NETHERITE) {
            return config.nether().enabled();
        }
        return true;
    }

    /**
     * Pasivní objevení portálu do Endu: bot, který o žádném neví, se čas od
     * času porozhlédne po portálových blocích v okolí (stronghold, custom
     * mapa, admin teleport doprostřed portálové síně). Nalezený portál se
     * uloží jako PORTAL {@code type=end} a šíří se drby.
     */
    private void noticeEndPortal() {
        WorldView view = worldView;
        BotPhysics phys = physics;
        if (view == null || phys == null
                || view.dimension() != dev.botalive.core.world.WorldDimension.OVERWORLD) {
            return;
        }
        Vec3 pos = phys.position();
        if (dev.botalive.core.ai.EndKnowledge.nearestEndPortal(
                memory, view.worldName(), (int) pos.x(), (int) pos.z()).isPresent()) {
            return; // o portálu už ví – není co hledat
        }
        BlockPos feet = pos.toBlockPos();
        int radius = 10;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = feet.offset(dx, dy, dz);
                    // Jen aktivní portál (END_PORTAL): prázdný rám bez očí se
                    // nepamatuje – bot ho neumí zapálit (oči chtějí Nether)
                    // a výprava k němu by skončila marným hledáním a smazáním.
                    if (view.materialAt(p) == org.bukkit.Material.END_PORTAL) {
                        memory.remember(MemoryKind.PORTAL, view.worldName(),
                                p.x(), p.y(), p.z(), null,
                                Map.of("type", dev.botalive.core.ai.EndKnowledge.TYPE_END),
                                0.9);
                        LOG.info("[{}] objevil portál do Endu na {} {} {} ({})",
                                name, p.x(), p.y(), p.z(), view.worldName());
                        if (rng.chance(0.6)) {
                            chat.sayFrom(PhraseCategory.PORTAL_FOUND, null);
                        }
                        return;
                    }
                }
            }
        }
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

    /**
     * Násobič utility podle pracovní smlouvy (najatý dělník se soustředí
     * na práci, bodyguard se drží šéfa). Bez smlouvy 1.0.
     *
     * @param goalId id cíle
     * @return násobič
     */
    public double employmentWeight(String goalId) {
        var employment = services.employment();
        return employment == null ? 1.0 : employment.weight(id, goalId);
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
        if (worldView == null) {
            return where;
        }
        // Lidská odpověď na „kde jsi": dimenze srozumitelně, svět jen jménem.
        return switch (worldView.dimension()) {
            case END -> where + " v Endu";
            case NETHER -> where + " v Netheru";
            default -> where + " (" + worldView.worldName() + ")";
        };
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
        // Vlastní vesnice má přednost – tu bot zná jménem. Jen ve stejném
        // světě a jen se zapnutými vesnicemi (vypnuté = chování jako dřív).
        var settlements = services.settlements();
        if (settlements != null && config.settlement().enabled()) {
            var own = settlements.settlementOf(id);
            if (own.isPresent() && own.get().world().equals(worldView.worldName())) {
                var center = own.get().center();
                return "bydlim v %s, naves je u %d, %d, %d".formatted(
                        own.get().name(), center.x(), center.y(), center.z());
            }
            var nearest = settlements.nearestSettlement(worldView.worldName(),
                    pos.toBlockPos(), 400);
            if (nearest.isPresent()) {
                var center = nearest.get().center();
                return "%s je u %d, %d, %d".formatted(nearest.get().name(),
                        center.x(), center.y(), center.z());
            }
        }
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
        pendingShare = new dev.botalive.core.ai.ShareRequest(requester, java.util.List.of());
        return brain.forceGoal("share");
    }

    @Override
    public boolean helpRequest(UUID requester) {
        // Na pomoc vyráží stateční a ochotní; kamarádovi skoro každý.
        double courage = personality.trait(dev.botalive.api.personality.Trait.COURAGE);
        double helpfulness = personality.trait(dev.botalive.api.personality.Trait.HELPFULNESS);
        boolean friend = memory.recallAbout(requester).stream()
                .anyMatch(r -> r.kind() == MemoryKind.FRIEND);
        if (!friend && courage + helpfulness < 0.9) {
            return false;
        }
        // Dojít za volajícím; boj u něj převezme bojová AI (hrozby, moby).
        return brain.forceGoal("follow");
    }

    @Override
    public boolean giveItemRequest(UUID requester, java.util.List<org.bukkit.Material> wanted) {
        var snapshot = serverView.latest();
        if (snapshot == null || wanted.isEmpty()
                || !snapshot.hasItem(wanted::contains)) {
            return false;
        }
        double helpfulness = personality.trait(dev.botalive.api.personality.Trait.HELPFULNESS);
        double greed = personality.trait(dev.botalive.api.personality.Trait.GREED);
        boolean friend = memory.recallAbout(requester).stream()
                .anyMatch(r -> r.kind() == MemoryKind.FRIEND);
        // Chamtiví boti se s cizími nedělí, i když jsou jinak ochotní.
        if (!friend && helpfulness < 0.4 + greed * 0.35) {
            return false;
        }
        pendingShare = new dev.botalive.core.ai.ShareRequest(requester, java.util.List.copyOf(wanted));
        return brain.forceGoal("share");
    }

    @Override
    public boolean marketBuyRequest(UUID sender, String senderName) {
        if (!config.economy().enabled() || !config.economy().playerTrade()
                || !config.economy().botTrade()) {
            return false;
        }
        // Bez Vaultu nejde ověřit příchozí /pay – prodej hráčům je vypnutý.
        if (!(wallet instanceof dev.botalive.core.economy.VaultBotWallet)) {
            return false;
        }
        // Boti kupují přes MarketBoard (BuyGoal) – jejich „beru!" v chatu je
        // jen řeč; tohle je vstup pro skutečné hráče.
        var graph = services.socialGraph();
        var market = services.market();
        if (graph == null || market == null || graph.isBot(sender)) {
            return false;
        }
        var offer = market.activeOffer(id);
        return offer.isPresent() && market.claim(offer.get().id(), sender, senderName);
    }

    @Override
    public int nearbyPlayerCount() {
        if (physics == null) {
            return 0;
        }
        return entities.nearby(physics.position(), 16, TrackedEntity::isPlayer).size();
    }

    @Override
    public dev.botalive.core.ai.ShareRequest takeShareRequest() {
        dev.botalive.core.ai.ShareRequest request = pendingShare;
        pendingShare = null;
        return request;
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
     * @param crimeLog    sdílená kniha zločinů
     * @param settlements sdílená služba vesnic
     * @param diplomacy   diplomacie sídel (napětí, války, příměří)
     * @param socialGraph sociální adresář (bot vs. hráč, drby)
     * @param market      tržiště botů (prodej hráčům přes chat)
     * @param employment  najímání botů hráči (smlouvy, mzdy)
     * @param authority   vydavatel a ověřovatel přihlašovacích pověření botů
     */
    public record SharedServices(
            BotAliveConfig config,
            WorldViewRegistry worldViews,
            MainThreadBridge bridge,
            BotTickEngine tickEngine,
            NavigationService navigation,
            BotRepository repository,
            dev.botalive.core.chat.PhraseBank phrases,
            dev.botalive.core.social.CrimeLog crimeLog,
            dev.botalive.core.settlement.SettlementService settlements,
            dev.botalive.core.settlement.DiplomacyService diplomacy,
            dev.botalive.core.social.SocialGraph socialGraph,
            dev.botalive.core.economy.MarketBoard market,
            dev.botalive.core.economy.EmploymentService employment,
            dev.botalive.core.gateway.CredentialAuthority authority,
            dev.botalive.core.role.RoleRegistryImpl roles
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

    /** Počká na zhasnutí síťového spojení (vypnutí pluginu, po odpojení). */
    public void awaitNetworkQuiesce(long millis) {
        connection.awaitQuiesce(millis);
    }

    /** Trvale odstraní bota (volá manager). */
    public void markRemoved() {
        removed.set(true);
        tickEngine.stopTicking(this);
        synchronized (tickLock) {
            brain.halt();
            navigator.stop();
        }
        flushPersistentState();
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
        flushPersistentState();
        connection.disconnect(reason);
        state.set(BotLifecycleState.DISCONNECTED);
    }

    /**
     * Dopíše statistiky a pozici při odchodu bota.
     *
     * <p>Dřív se obojí ukládalo jen z tick smyčky à 1200 ticků, takže
     * {@code /stop} zahodil až 60 s nasbíraných statistik u každého bota.
     */
    private void flushPersistentState() {
        try {
            stats.flush();
            persistPosition();
        } catch (RuntimeException e) {
            LOG.warn("[{}] Uložení stavu při odchodu selhalo: {}", name, e.toString());
        }
    }

    // ======================================================================
    // NetworkEvents – volané ze síťového vlákna
    // ======================================================================

    @Override
    public void onLogin(int entityId, String worldKey) {
        state.set(BotLifecycleState.CONFIGURING);
        reconnectAttempts.set(0);
        awaitingFirstTeleport = true;
        // Přerušený portálový přechod (kick/timeout mezi respawnem a prvním
        // teleportem) nesmí po reconnectu zapsat falešný „přílet" na spawnu.
        portalArrivedFrom = null;
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
                (int) pos.x(), (int) pos.y(), (int) pos.z(), null,
                Map.of("cause", deathMessage == null ? "" : deathMessage), 0.8);
        gainExperience(dev.botalive.core.personality.PersonalityEvolution.BotExperience.DEATH);
        if (services.diplomacy() != null) {
            services.diplomacy().noteDeath(id); // válečné ztráty (padne-li ve válce)
        }
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
        // Respawn paket zaživa = změna dimenze. PORTAL vzpomínka (odchod hned,
        // přílet při prvním teleportu v novém světě) se ale zapisuje jen při
        // skutečném průchodu portálem – bot musí stát v portálových blocích.
        // Plugin/admin teleporty mezi světy by jinak zakládaly fantomové
        // portály, ke kterým by se boti marně vraceli.
        WorldView oldWorld = worldView;
        BotPhysics oldPhysics = physics;
        portalArrivedFrom = null;
        if (!afterDeath && oldWorld != null && oldPhysics != null
                && !oldWorld.worldName().equals(expectedWorldName(worldKey))) {
            Vec3 pos = oldPhysics.position();
            BlockPos feet = pos.toBlockPos();
            boolean throughPortal = oldWorld.traitsAt(feet).portal()
                    || oldWorld.traitsAt(feet.up()).portal();
            if (throughPortal) {
                memory.remember(MemoryKind.PORTAL, oldWorld.worldName(),
                        feet.x(), feet.y(), feet.z(), null,
                        Map.of("to", expectedWorldName(worldKey)), 0.7);
                portalArrivedFrom = oldWorld.worldName();
            }
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

    /** Stuck watchdog: práh nehybnosti při aktivní navigaci (600 ticků = 30 s). */
    private static final int WATCHDOG_STILL_TICKS = 600;
    /** Poslední pozice s pohybem a stáří nehybnosti (jen tick vlákno). */
    private Vec3 watchdogPos = Vec3.ZERO;
    private int watchdogStillTicks;
    /** Opakované watchdog resety na témže bloku → nouzový přesun. */
    private BlockPos watchdogLastResetPos;
    private int watchdogRepeats;
    /** Čas posledního nouzového přesunu – 2. přesun v okně jde na povrch. */
    private long watchdogLastRelocateAt;
    /** Okno, ve kterém druhý nouzový přesun eskaluje na povrch (ms). */
    private static final long WATCHDOG_RELOCATE_WINDOW_MS = 300_000L;

    /** @return ticky nehybnosti při aktivní navigaci (pro flotilový přehled) */
    public int ticksWithoutMovement() {
        return watchdogStillTicks;
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

        // Periodický snapshot serverového hráče a flush statistik.
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
        // Sousedská úvaha (vesnice) – rozfázovaně, jen u živého aktivního bota.
        if (++ticksSinceCohesion >= 600 + (int) (Math.abs(id.getLeastSignificantBits()) % 300)
                && !clientState.dead() && !paused.get()
                && state.get() == BotLifecycleState.SPAWNED) {
            ticksSinceCohesion = 0;
            tickSettlementCohesion();
        }
        // Vítání hráčů ve vesnici – rozfázovaně (jiný jitter než cohesion).
        if (++ticksSinceWelcome >= 300 + (int) (Math.abs(id.getMostSignificantBits()) % 200)
                && !clientState.dead() && !paused.get()
                && state.get() == BotLifecycleState.SPAWNED) {
            ticksSinceWelcome = 0;
            tickVillageWelcome();
        }
        // Diplomatická úvaha – řídce; skutečně rozhoduje jen starosta.
        if (++ticksSinceDiplomacy >= 900 + (int) (Math.abs(id.getLeastSignificantBits()) % 400)
                && !clientState.dead() && !paused.get()
                && state.get() == BotLifecycleState.SPAWNED) {
            ticksSinceDiplomacy = 0;
            if (services.diplomacy() != null) {
                services.diplomacy().maybeTick(this);
            }
        }
        // Pracovní smlouvy – častěji (čeká se na příchozí /pay platbu).
        if (++ticksSinceEmployment >= 200 + (int) (Math.abs(id.getMostSignificantBits()) % 100)
                && !clientState.dead() && !paused.get()
                && state.get() == BotLifecycleState.SPAWNED) {
            ticksSinceEmployment = 0;
            if (services.employment() != null) {
                services.employment().tick(this);
            }
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
            // Vozidlová úloha (plavba člunem za cílem) řídí kurz i vysednutí –
            // musí se tickovat i tady, jinak by po nasednutí ztratila kontrolu.
            if (obstacleTask instanceof dev.botalive.core.tasks.VehicleTask
                    && alive && !paused.get() && obstacleTask.tick(this)) {
                obstacleTask = null;
                boatCheckCooldown = 200;
                striderCheckCooldown = 200;
                navigator.assistResolved(physics.position());
            }
            vehicle.tick();
            chat.tick();
            return;
        }

        // Zdolávání překážek: když navigace narazí (zazděno, jáma, mezera),
        // bot si cestu odblokuje sám – prokopáním nebo položením bloku.
        if (obstacleTask != null && (!alive || paused.get())) {
            obstacleTask.cancel(this);
            obstacleTask = null;
            actionTaskRunning = false;
        }
        MoveInput input;
        boolean navDriven = false;
        if (obstacleTask != null) {
            if (obstacleTask.tick(this)) {
                if (obstacleTask instanceof dev.botalive.core.tasks.VehicleTask) {
                    boatCheckCooldown = 200; // po přejezdu/neúspěchu loď hned nezkoušet
                    striderCheckCooldown = 200;
                }
                obstacleTask = null;
                if (actionTaskRunning) {
                    // Zásah z plánu cesty vykonán → pokračovat po TÉŽE cestě
                    // (žádný replán – to je celá pointa kopacích hran).
                    actionTaskRunning = false;
                    navigator.actionResolved();
                } else {
                    navigator.assistResolved(physics.position());
                }
                input = MoveInput.IDLE;
            } else {
                input = obstacleTask.move(); // most se posouvá, ostatní zásahy stojí
            }
        } else {
            // Nouzové vyproštění: bot je (dle world view) uvnitř pevného bloku
            // a dusí se (nepovedený výkop, zásyp). Přednost přede vším – na
            // vyproštění je ~10 s. Krátká stabilizace filtruje průlety rohem.
            if (obstacleTask == null && alive && !paused.get() && worldView != null) {
                BlockPos trapped = dev.botalive.core.physics.Suffocation
                        .trappedIn(worldView, physics.position());
                if (trapped != null) {
                    if (++buriedTicks >= 10) {
                        var material = worldView.materialAt(trapped);
                        inventoryHelper.equipBestTool(serverView.latest(),
                                material != null ? material : org.bukkit.Material.STONE);
                        obstacleTask = new dev.botalive.core.tasks.MineBlockTask(trapped);
                        LOG.debug("[{}] [rescue] zazděný v {} – kopu se ven", name, trapped);
                        buriedTicks = 0;
                    }
                } else {
                    buriedTicks = 0;
                }
            }
            if (alive && !paused.get() && !combat.engaged() && navigator.actionNeeded() != null) {
                // Zásah do terénu z plánu cesty: vykopat bloky a pokračovat
                // po téže cestě (kopací hrany – náhrada assist eskalace).
                obstacleTask = plannedActionSequence(navigator.actionNeeded());
                actionTaskRunning = obstacleTask != null;
                LOG.debug("[{}] [nav] zásah z plánu: {} kopat, {} položit, žebřík {}", name,
                        navigator.actionNeeded().digs().size(),
                        navigator.actionNeeded().places().size(),
                        navigator.actionNeeded().ladder() != null
                                ? navigator.actionNeeded().ladder().height() : 0);
                if (obstacleTask == null) {
                    navigator.actionResolved(); // prázdný zásah – jen pokračovat
                }
            } else if (alive && !paused.get() && !combat.engaged() && navigator.needsAssist()) {
                obstacleTask = planObstacleRecovery();
                LOG.debug("[{}] [nav] assist: plán={}", name,
                        obstacleTask == null ? "žádný (vzdávám)" : obstacleTask.getClass().getSimpleName());
                if (obstacleTask == null) {
                    navigator.assistFailed(physics.position());
                }
            } else if (alive && !paused.get() && !combat.engaged()
                    && config.ai().boats() && !"boat".equals(brain.currentGoalId())
                    && shouldBoardBoat()) {
                // Široká voda ve směru cíle + dostupná loď → přeplout místo plavání.
                // (Rekreační cíl „boat" si loď řídí sám – tomu do toho nesahat.)
                obstacleTask = new dev.botalive.core.tasks.WaterCrossTask(navigator.currentObjective());
                LOG.debug("[{}] [nav] loď: přejezd vody k {}", name, navigator.currentObjective());
            } else if (alive && !paused.get() && !combat.engaged()
                    && shouldBoardStrider()) {
                // Lávový oceán ve směru cíle (širší než reaktivní most) +
                // strider a jezdecká výbava → osedlat a přejet místo obcházení.
                obstacleTask = new dev.botalive.core.tasks.LavaCrossTask(navigator.currentObjective());
                LOG.debug("[{}] [nav] strider: přejezd lávy k {}", name, navigator.currentObjective());
            }
            // Pohyb: explicitní požadavek cíle > navigace > stání.
            if (requestedMove != null) {
                input = requestedMove;
            } else {
                input = navigator.tick(physics.position(), physics.onGround(), physics.inWater());
                navDriven = navigator.hasPath();
            }
        }
        if (!alive || paused.get()) {
            input = MoveInput.IDLE;
        }

        // Vyhýbání davu: odpuzuj se od blízkých hráčů/botů, ať se boti neslévají
        // na stejné místo. Platí i v boji – tam se ale vyloučí cíl útoku, aby se
        // bot pořád mohl přiblížit k protivníkovi, jen se nehromadil na ostatních
        // útočnících stejného cíle. Běží PŘED záchrannými reflexy – dav nesmí
        // ohnout únikový směr tonoucího/hořícího bota (reflexy mají poslední slovo).
        // Přesným taskům dav USTOUPÍ: pilíř a žebřík drží střed sloupce,
        // pokládka a kopání míří na blok – strčení kolemjdoucího by stavitele
        // shodilo z rozestavěného pilíře nebo rozhodilo klik. Kolemjdoucí se
        // vyhne sám (jeho steering stojícího vidí).
        if (alive && !paused.get() && obstacleTask == null && navigator.actionNeeded() == null) {
            int targetId = combat.engaged() && combat.target() != null
                    ? combat.target().entityId() : -1;
            List<TrackedEntity> crowd = entities.nearby(physics.position(), CrowdAvoidance.radius(),
                    e -> e.blocksMovement() && e.entityId() != targetId);
            if (!crowd.isEmpty()) {
                Vec3 steered = CrowdAvoidance.steer(
                        physics.position(), clientState.entityId(), crowd, input.direction(),
                        worldView);
                if (!steered.equals(input.direction())) {
                    input = new MoveInput(steered, input.sprint(), input.jump(), input.sneak());
                }
            }
        }

        // Tekutinové reflexy: útěk z lávy a dušení přebíjí vše, vynoření z vody
        // chrání boty bez aktivní plavecké navigace (stojící, ručně řízené cíle).
        if (alive && !paused.get() && worldView != null) {
            MoveInput beforeLiquid = input;
            input = dev.botalive.core.physics.LiquidReflex.apply(
                    input, navDriven, physics.position(), physics.submergedTicks(), worldView);
            if (input != beforeLiquid && physics.submergedTicks() > 100) {
                LOG.debug("[{}] [reflex] dech {} ticků pod vodou, únik směr {}",
                        name, physics.submergedTicks(), input.direction());
            }
            // Prašan: zabořený bot mrzne – vyhrabat se skokem k nejbližšímu
            // bezpečí. Přebíjí vše, v prašanu nemá žádný cíl co pohledávat.
            input = dev.botalive.core.physics.PowderSnowReflex.apply(
                    input, physics.inPowderSnow(), physics.position(), worldView);
        }

        // Pádový reflex: záchranná síť kolem pádů. Na zemi – když bota u
        // nebezpečné hrany postrčí dav (nebo se jen tak přišourá), přikrčí se
        // a ochrana hrany ho zadrží; řízený pohyb (navigace s cestou,
        // most/žebřík/loď, cíl s requestedMove) ví, co dělá, do toho nesahat.
        // Ve vzduchu – bot padající do nebezpečné hloubky kormidluje k vodě
        // nebo měkkému bloku v dosahu (clutch; zasahuje i do řízeného pohybu,
        // hluboký pád mimo vodu není nikdy v plánu).
        boolean movementManaged = navDriven || obstacleTask != null || requestedMove != null;
        if (alive && !paused.get() && worldView != null) {
            input = dev.botalive.core.physics.FallReflex.apply(
                    input, movementManaged, physics.onGround(), physics.fallDistance(),
                    physics.position(), worldView);
        }

        // Přirozený pohled ve směru chůze (pokud cíl neřídí pohled sám).
        if (requestedMove == null && input.direction().horizontalLength() > 1.0E-4) {
            humanizer.lookAlong(input.direction());
        }

        // Zásah do terénu (míření na blok/hladinu) není „nicnedělání" – jinak by
        // idle-rozhlížení humanizeru přepsalo namířený pohled (kritické pro
        // pokládání lodi přes raycast v {@link dev.botalive.core.tasks.WaterCrossTask}).
        boolean idle = input == MoveInput.IDLE && !combat.engaged() && obstacleTask == null;
        humanizer.tick(physics.position().add(0, 1.62, 0), idle && alive);

        // Pohybové efekty (levitace po zásahu shulkerem, slow falling):
        // server je aplikuje autoritativně, klientská simulace je musí
        // replikovat, jinak se pozice rozjedou (rubberbanding).
        physics.effects(
                clientState.effectActive(org.geysermc.mcprotocollib.protocol.data.game
                        .entity.Effect.LEVITATION),
                clientState.effectActive(org.geysermc.mcprotocollib.protocol.data.game
                        .entity.Effect.SLOW_FALLING));
        physics.step(input);
        movementSender.tick(physics.position(), humanizer.yaw(), humanizer.pitch(),
                physics.onGround(), physics.horizontalCollision(), input);

        // Diagnostika tvrdých dopadů (poškození řeší server; tady jen záznam).
        if (physics.landedThisTick() && physics.lastFallDamage() > 0) {
            LOG.debug("[{}] tvrdý dopad z ~{} bloků (odhad {} poškození)",
                    name, String.format("%.1f", physics.lastFallDistance()), physics.lastFallDamage());
        }

        // Stuck watchdog: aktivní navigace bez pohybu 30 s = zaseknutí, které
        // nižší vrstvy nezachytily (dveře, dav, kolizní desync klient×server).
        // WARN se souvislostmi a tvrdý reset navigace – goal si hned naplánuje
        // čerstvou cestu a trvale marný cíl srazí backoff v navigátoru.
        if (physics.position().distanceSquared(watchdogPos) > 0.04
                || !navigator.navigating()) {
            watchdogPos = physics.position();
            watchdogStillTicks = 0;
        } else if (++watchdogStillTicks >= WATCHDOG_STILL_TICKS) {
            watchdogStillTicks = 0;
            BlockPos here = physics.position().toBlockPos();
            if (here.equals(watchdogLastResetPos)) {
                watchdogRepeats++;
            } else {
                watchdogLastResetPos = here;
                watchdogRepeats = 0;
            }
            if (watchdogRepeats >= 2) {
                // Třetí reset na témže bloku: navigační resety nepomáhají, bot
                // je uvězněný v něčem, co klientský model nezná (stojí na
                // entitě – loď; kolizní desync). Poslední instance: přesun na
                // sousední pevnou buňku s plným resyncem klienta.
                watchdogRepeats = 0;
                watchdogLastResetPos = null;
                String worldName = worldView != null ? worldView.worldName() : null;
                World bukkitWorld = worldName != null ? Bukkit.getWorld(worldName) : null;
                long now = System.currentTimeMillis();
                boolean secondInWindow = now - watchdogLastRelocateAt
                        < WATCHDOG_RELOCATE_WINDOW_MS;
                watchdogLastRelocateAt = now;
                boolean overworld = worldView != null && worldView.dimension()
                        == dev.botalive.core.world.WorldDimension.OVERWORLD;
                if (secondInWindow && overworld && bukkitWorld != null) {
                    // 2. stupeň: sousední buňka nestačila (jeskynní geometrie
                    // poráží bota opakovaně) – přenést na povrch sloupce.
                    // Lávová „hladina" jako cíl neplatí, bezpečnější je spawn.
                    // Mimo overworld (střecha Netheru!) zůstává 1. stupeň.
                    LOG.warn("[{}] watchdog: 2. nouzový přesun během 5 min na {} "
                            + "– přenos na povrch", name, here);
                    bridge.runAt(new Location(bukkitWorld, here.x(), here.y(), here.z()), () -> {
                        int surfaceY = bukkitWorld.getHighestBlockYAt(here.x(), here.z());
                        var surface = bukkitWorld.getBlockAt(here.x(), surfaceY, here.z())
                                .getType();
                        Location target = surface == org.bukkit.Material.LAVA
                                ? bukkitWorld.getSpawnLocation()
                                : new Location(bukkitWorld, here.x() + 0.5, surfaceY + 1.0,
                                        here.z() + 0.5);
                        teleport(target);
                    });
                } else if (bukkitWorld != null) {
                    BlockPos refuge = findAdjacentGround(here);
                    LOG.warn("[{}] watchdog: 3× reset na {} bez posunu – nouzový přesun na {}",
                            name, here, refuge);
                    teleport(new Location(bukkitWorld, refuge.x() + 0.5, refuge.y(),
                            refuge.z() + 0.5));
                }
            } else {
                LOG.warn("[{}] watchdog: {} s bez pohybu na {} (goal {}, cíl {}) – reset navigace",
                        name, WATCHDOG_STILL_TICKS / 20, here,
                        brain.currentGoalId(), navigator.currentObjective());
            }
            navigator.stop();
        }

        // Periodicky: přepočet postupu k životní ambici (levné, cache 2 s).
        if (--ambitionRefreshTicks <= 0) {
            ambitionRefreshTicks = 40;
            refreshAmbition();
        }

        // Periodicky: všimnout si portálu do Endu v okolí (kdo žádný nezná).
        // Takhle se znalost dostane do systému: admin teleportuje bota do
        // strongholdu (nebo bot pevnost proleze sám) a on si portál uloží;
        // drby ji pak roznesou dál. Bez zapnutých výprav se neskenuje.
        if (alive && !paused.get() && config.end().enabled() && --portalScanTicks <= 0) {
            portalScanTicks = 600 + rng.rangeInt(0, 200);
            noticeEndPortal();
        }

        // Periodicky: sebrané brnění nasadit + štít do druhé ruky. V Netheru
        // se zlaté boty nechávají na nohou (piglini) – jinak by je tier
        // logika hned přezula zpátky a rozbila neutralitu i barter.
        if (alive && !paused.get() && !combat.engaged() && --armorCheckTicks <= 0) {
            armorCheckTicks = 100 + rng.rangeInt(0, 60);
            var snapshot = serverView.latest();
            boolean pinGold = worldView != null
                    && worldView.dimension() == dev.botalive.core.world.WorldDimension.NETHER;
            if (!inventoryHelper.equipBetterArmor(snapshot,
                    humanizer.yaw(), humanizer.pitch(), pinGold)
                    && snapshot != null
                    && snapshot.offhand() != org.bukkit.Material.SHIELD
                    && inventoryHelper.equipItem(snapshot, org.bukkit.Material.SHIELD)) {
                actions.swapOffhand();
            }
        }

        environmentChatter();
        chat.tick();
        trackDistance();
    }

    // ------------------------------------------------- reakce na dění kolem

    /** Poslední chatová reakce na útok (ms) – jedna hláška na potyčku. */
    private long lastAttackReactMs;
    /** Stav prostředí pro hrany (soumrak, hlad, málo životů). */
    private long prevWorldTime = -1;
    private int prevFood = 20;
    private float prevHealth = 20;

    /**
     * Chatová reakce na útok (volá {@code ServerEventListener} z herního
     * vlákna). Jedna hláška na potyčku – další rány už bot nekomentuje,
     * řeší je bojová AI.
     *
     * @param byPlayer {@code true} útočil hráč/bot, {@code false} mob
     */
    public void onAttackedChat(boolean byPlayer) {
        long now = System.currentTimeMillis();
        if (now - lastAttackReactMs < 30_000 || clientState.dead()) {
            return;
        }
        lastAttackReactMs = now;
        if (rng.chance(0.7)) {
            chat.sayUrgent(byPlayer ? dev.botalive.core.chat.PhraseCategory.ATTACKED
                    : dev.botalive.core.chat.PhraseCategory.HURT_BY_MOB, null);
        }
    }

    /**
     * Změna počasí ve světě bota (volá {@code ServerEventListener}).
     * Jen občasný komentář – guvernér chatu tlumí dav.
     *
     * @param thunder {@code true} začala bouřka, {@code false} začal déšť
     */
    public void onWeatherChanged(boolean thunder) {
        if (clientState.dead() || paused.get()) {
            return;
        }
        double sociability = personality.trait(dev.botalive.api.personality.Trait.SOCIABILITY);
        if (rng.chance(0.10 + sociability * 0.12)) {
            chat.sayFrom(thunder ? dev.botalive.core.chat.PhraseCategory.WEATHER_THUNDER
                    : dev.botalive.core.chat.PhraseCategory.WEATHER_RAIN, null);
        }
    }

    /**
     * Hrany herních mechanik → občasné hlášky: soumrak, hlad, málo životů.
     * Vše jde přes spontánní guvernér chatu (rozestupy, tlumení v davu),
     * takže z 30 botů okomentuje soumrak jen pár – jako na skutečném serveru.
     */
    private void environmentChatter() {
        if (clientState.dead() || paused.get()) {
            return;
        }
        // Soumrak: čas překročil ~12800 (mobové za chvíli venku).
        long time = worldTime();
        if (time >= 0) {
            if (prevWorldTime >= 0 && prevWorldTime < 12_800 && time >= 12_800
                    && rng.chance(0.25)) {
                chat.sayFrom(dev.botalive.core.chat.PhraseCategory.NIGHTFALL, null);
            }
            prevWorldTime = time;
        }
        // Hlad: kleslo jídlo pod 6 (začíná být vážné).
        int food = clientState.food();
        if (prevFood > 6 && food <= 6 && rng.chance(0.5)) {
            chat.sayFrom(dev.botalive.core.chat.PhraseCategory.HUNGRY, null);
        }
        prevFood = food;
        // Málo životů: kleslo zdraví pod 6 (3 srdce).
        float health = clientState.health();
        if (prevHealth > 6 && health <= 6 && health > 0 && rng.chance(0.6)) {
            chat.sayUrgent(dev.botalive.core.chat.PhraseCategory.LOW_HEALTH, null);
        }
        prevHealth = health;
    }

    /**
     * Rozhodne, jestli si teď vzít loď na překonání vody.
     *
     * <p>Na rozdíl od terénních zásahů se loď nespouští ze zaseknutí (A* vodu
     * přeplave, takže navigace neuvázne), ale proaktivně: navigace míří přes
     * souvislou vodu širší než {@link dev.botalive.core.vehicle.Boats#MIN_CROSS_WIDTH}
     * a bot má loď (v inventáři nebo poblíž na hladině). Loď je mnohem rychlejší
     * než plavání. Po přejezdu/neúspěchu drží {@link #boatCheckCooldown} odstup,
     * aby bot u nedosažitelného cíle loď nezkoušel donekonečna.</p>
     *
     * @return {@code true} když se teď vyplatí nasednout do lodi
     */
    private boolean shouldBoardBoat() {
        if (boatCheckCooldown > 0) {
            boatCheckCooldown--;
            return false;
        }
        if (worldView == null || physics == null || !navigator.navigating()) {
            return false;
        }
        BlockPos dest = navigator.currentObjective();
        if (dest == null) {
            return false;
        }
        BlockPos feet = physics.position().toBlockPos();
        boolean preferX = Math.abs(dest.x() - feet.x()) >= Math.abs(dest.z() - feet.z());
        int sx = preferX ? Integer.signum(dest.x() - feet.x()) : 0;
        int sz = preferX ? 0 : Integer.signum(dest.z() - feet.z());
        if (sx == 0 && sz == 0) {
            return false;
        }
        if (dev.botalive.core.vehicle.Boats.openWaterWidth(worldView, feet, sx, sz)
                < dev.botalive.core.vehicle.Boats.MIN_CROSS_WIDTH) {
            return false;
        }
        var snapshot = serverView.latest();
        boolean hasItem = snapshot != null
                && snapshot.hasItem(dev.botalive.core.vehicle.Boats::isBoatItem);
        boolean boatNearby = entities.nearest(physics.position(), 12,
                e -> dev.botalive.core.vehicle.Boats.isBoatType(e.type().name())).isPresent();
        return hasItem || boatNearby;
    }

    /**
     * Vyplatí se osedlat stridera? Lávová analogie {@link #shouldBoardBoat()}:
     * navigace míří přes souvislou lávu širší než
     * {@link dev.botalive.core.vehicle.Striders#MIN_CROSS_WIDTH} (užší řeší
     * reaktivní most/obchůzka), bot má houbu na prutu (řízení) a poblíž se
     * brouzdá strider. Sedlo je ideál, ne podmínka – strider už může být
     * osedlaný z dřívějšího pokusu a marné nasedání ukončí cooldown.
     *
     * @return {@code true} když se teď vyplatí přejet lávu na striderovi
     */
    private boolean shouldBoardStrider() {
        if (striderCheckCooldown > 0) {
            striderCheckCooldown--;
            return false;
        }
        if (worldView == null || physics == null || !navigator.navigating()
                || !config.nether().striders()
                || worldView.dimension() != dev.botalive.core.world.WorldDimension.NETHER) {
            return false;
        }
        BlockPos dest = navigator.currentObjective();
        if (dest == null) {
            return false;
        }
        BlockPos feet = physics.position().toBlockPos();
        boolean preferX = Math.abs(dest.x() - feet.x()) >= Math.abs(dest.z() - feet.z());
        int sx = preferX ? Integer.signum(dest.x() - feet.x()) : 0;
        int sz = preferX ? 0 : Integer.signum(dest.z() - feet.z());
        if (sx == 0 && sz == 0) {
            return false;
        }
        if (dev.botalive.core.vehicle.Striders.openLavaWidth(worldView, feet, sx, sz)
                < dev.botalive.core.vehicle.Striders.MIN_CROSS_WIDTH) {
            return false;
        }
        var snapshot = serverView.latest();
        if (snapshot == null
                || !snapshot.hasItem(dev.botalive.core.vehicle.Striders::isSteeringRod)) {
            return false;
        }
        return entities.nearest(physics.position(), 16,
                e -> e.type() == org.geysermc.mcprotocollib.protocol.data.game.entity
                        .type.EntityType.STRIDER).isPresent();
    }

    /** Počet stavebních bloků v inventáři – rozpočet pokládacích hran plánu. */
    private int buildingBlockBudget() {
        return dev.botalive.core.inventory.InventoryHelper
                .countBuildingBlocks(serverView.latest());
    }

    /** Počet žebříků v inventáři (rozpočet žebříkových hran plánu). */
    private int ladderBudget() {
        return dev.botalive.core.inventory.InventoryHelper
                .countItem(serverView.latest(), org.bukkit.Material.LADDER);
    }

    /**
     * Sestaví sekvenci vykonání jednoho zásahu z plánu cesty
     * ({@link dev.botalive.core.pathfinding.TerrainAction}): vykopání bloků
     * (s per-blokovým nasazením nejlepšího nástroje; blok mezitím zmizelý se
     * přeskočí) a položení bloků (opora mostu, pilíř; bez stavebních bloků
     * v inventáři se krok vzdá a stav dořeší detekce zaseknutí – další plán
     * už počítá s nulovým rozpočtem pokládání).
     *
     * @param action zásah z plánu
     * @return sekvence tasků, nebo {@code null} při prázdném zásahu
     */
    private dev.botalive.core.tasks.BotTask plannedActionSequence(
            dev.botalive.core.pathfinding.TerrainAction action) {
        java.util.List<dev.botalive.core.tasks.BotTask> steps = new java.util.ArrayList<>();
        for (BlockPos dig : action.digs()) {
            steps.add(new dev.botalive.core.tasks.BotTask() {
                private dev.botalive.core.tasks.MineBlockTask mine;

                @Override
                public boolean tick(dev.botalive.core.ai.BotContext ctx) {
                    if (mine == null) {
                        if (worldView == null || !worldView.traitsAt(dig).solid()) {
                            return true; // blok už nestojí
                        }
                        var material = worldView.materialAt(dig);
                        inventoryHelper.equipBestTool(serverView.latest(),
                                material != null ? material : org.bukkit.Material.STONE);
                        mine = new dev.botalive.core.tasks.MineBlockTask(dig);
                    }
                    return mine.tick(ctx);
                }

                @Override
                public void cancel(dev.botalive.core.ai.BotContext ctx) {
                    if (mine != null) {
                        mine.cancel(ctx);
                    }
                }
            });
        }
        for (BlockPos place : action.places()) {
            steps.add(new dev.botalive.core.tasks.BotTask() {
                private dev.botalive.core.tasks.PlaceBlockTask task;

                @Override
                public boolean tick(dev.botalive.core.ai.BotContext ctx) {
                    if (task == null) {
                        if (worldView == null || worldView.traitsAt(place).solid()) {
                            return true; // blok už stojí
                        }
                        if (!inventoryHelper.equipBuildingBlock(serverView.latest())) {
                            return true; // bez bloků – dořeší detekce zaseknutí
                        }
                        task = new dev.botalive.core.tasks.PlaceBlockTask(place);
                    }
                    return task.tick(ctx);
                }

                @Override
                public void cancel(dev.botalive.core.ai.BotContext ctx) {
                    if (task != null) {
                        task.cancel(ctx);
                    }
                }
            });
        }
        if (action.ladder() != null) {
            // Žebříkový výstup: LadderTask nalepí sloupec příček z footholdu
            // a vyleze jedním tahem – plán nese jen směr a výšku, mechaniku
            // (míření, verifikace, plynulý šplh) vlastní task.
            steps.add(new dev.botalive.core.tasks.LadderTask(
                    action.ladder().sx(), action.ladder().sz()));
        }
        return steps.isEmpty() ? null : new dev.botalive.core.tasks.TaskSequence(steps);
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
        BlockPos destination = navigator.currentObjective();
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

        // Tekutina v cestě (lávové jezero, hluboká voda) → přemostit směrem
        // k cíli. Klasika hráče: blok do hladiny, krok, další blok. Strop
        // lávového mostu drží konfigurace (nether.lava-bridge-limit) –
        // širší lávu řeší obchůzka nebo strider.
        boolean frontLiquid = worldView.traitsAt(front).liquid();
        boolean belowLiquid = worldView.traitsAt(front).passable()
                && worldView.traitsAt(front.down()).liquid();
        if ((frontLiquid || belowLiquid)
                && inventoryHelper.equipBuildingBlock(serverView.latest())) {
            boolean lava = (frontLiquid ? worldView.traitsAt(front)
                    : worldView.traitsAt(front.down())).hazard();
            int limit = lava ? config.nether().lavaBridgeLimit()
                    : dev.botalive.core.tasks.BridgeTask.DEFAULT_MAX_SEGMENTS;
            return new dev.botalive.core.tasks.BridgeTask(sx, sz, limit);
        }

        // Propast v cestě (mezera mezi ostrovy Endu, kaňon) → stejný most,
        // jen deck visí nad prázdnem. Cíl přibližně v úrovni bota nebo výš –
        // k cíli dole se nemostí, tam vede sestup jinudy.
        if (dy >= 0 && worldView.traitsAt(front).passable()
                && chasmAhead(front)
                && inventoryHelper.equipBuildingBlock(serverView.latest())) {
            return new dev.botalive.core.tasks.BridgeTask(sx, sz);
        }

        // Svislá stěna vyšší než skok (front i patro nad ním pevné) a cíl výš →
        // přilepit žebřík a přelézt ji. Klasika hráče místo hloubení schodiště;
        // řeší i zdi se stropem, kudy se prokopat vzhůru nedá.
        if (dy > 0 && config.ai().ladders()
                && worldView.traitsAt(front).solid()
                && worldView.traitsAt(front.up()).solid()
                && !liquidNear(front)
                && inventoryHelper.equipItem(serverView.latest(), org.bukkit.Material.LADDER)) {
            return new dev.botalive.core.tasks.LadderTask(sx, sz);
        }

        // Cíl výrazně výš a před nosem nic pevného (převis, útes, plošina) →
        // vypilířovat se vzhůru: skok + blok pod nohy. Výš než MAX_HEIGHT se
        // staví nadvakrát – po dosažení vršku si navigace cestu přepočítá.
        if (dy > 1 && config.ai().pillaring()
                && !worldView.traitsAt(front).solid()
                && dev.botalive.core.tasks.PillarUpTask.columnClear(
                        worldView, feet, Math.min(destination.y(),
                                feet.y() + dev.botalive.core.tasks.PillarUpTask.MAX_HEIGHT))
                && inventoryHelper.equipBuildingBlock(serverView.latest())) {
            return new dev.botalive.core.tasks.PillarUpTask(destination.y());
        }

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
        // Vpředu nic pevného → mezera v podlaze: přemostit blokem. Jen krátkou
        // díru s dohledným protějším břehem – ne pochodovat po bloku do prázdna.
        if (dy <= 0 && worldView.traitsAt(front).passable()
                && worldView.traitsAt(front.down()).passable()
                && !liquidNear(front.down())
                && landingAhead(feet, sx, sz)
                && inventoryHelper.equipBuildingBlock(serverView.latest())) {
            return new dev.botalive.core.tasks.PlaceBlockTask(front.down());
        }
        return null;
    }

    /** Pevný břeh ve směru mostku do 4 bloků (podlaha v úrovni nohou). */
    private boolean landingAhead(BlockPos feet, int sx, int sz) {
        for (int i = 2; i <= 4; i++) {
            if (worldView.traitsAt(feet.offset(sx * i, -1, sz * i)).solid()) {
                return true;
            }
        }
        return false;
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
                        break;
                    }
                }
            }
            if (result.size() >= 24) {
                break;
            }
        }
        // Živé hrozby: viditelní hostilové vstupují do cen tras jako měkké
        // danger body – nové plány je obcházejí obloukem (COST_DANGER),
        // místo aby bot spoléhal na paniku uprostřed cesty. Aktuální cíl
        // boje se vynechává – k němu se přibližovat MÁ.
        if (physics != null && entities != null) {
            int combatTargetId = combat != null && combat.target() != null
                    ? combat.target().entityId() : -1;
            for (var hostile : entities.nearby(physics.position(), 24,
                    e -> e.isHostile() && e.entityId() != combatTargetId)) {
                if (hostile.position() == null) {
                    continue;
                }
                result.add(hostile.position().toBlockPos());
                if (result.size() >= 32) {
                    break;
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

    /**
     * Propast před botem: pod sloupcem {@code front} není v dohledné hloubce
     * nic pevného ani tekutina – jen vzduch, případně void (UNKNOWN pod
     * spodkem světa). Hloubka záměrně převyšuje bezpečný seskok.
     */
    private boolean chasmAhead(BlockPos front) {
        for (int depth = 1; depth <= 8; depth++) {
            var below = worldView.traitsAt(front.offset(0, -depth, 0));
            if (below == dev.botalive.core.world.BlockTraits.UNKNOWN) {
                // Nenačtený chunk nelze odlišit od voidu – nemostit naslepo,
                // ledaže jsme pod spodní hranou světa (tam už nic nebude).
                return front.y() - depth < worldView.minY();
            }
            if (below.solid() || below.liquid()) {
                return false;
            }
        }
        return true;
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
                // Zahřát chunk cache kolem cíle i bez změny světa: sken cílů
                // hned po teleportu jinak kouká do prázdna (async snapshoty
                // s TTL) a zbytečně to vzdá.
                if (worldView != null) {
                    worldView.prefetch(target.toBlockPos(), 2);
                }
            }

            if (awaitingFirstTeleport) {
                awaitingFirstTeleport = false;
                onSpawnComplete(target);
            }
        }
    }

    /**
     * Najde sousední buňku s pevnou podlahou a místem pro tělo – útočiště
     * nouzového přesunu watchdogu. Bez nálezu vrací výchozí pozici (samotný
     * resync klienta často desync srovná).
     */
    private BlockPos findAdjacentGround(BlockPos around) {
        if (worldView == null) {
            return around;
        }
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // jen obvod prstence
                    }
                    BlockPos cell = new BlockPos(around.x() + dx, around.y(), around.z() + dz);
                    if (worldView.traitsAt(cell).lowProfile()
                            && worldView.traitsAt(cell.up()).lowProfile()
                            && worldView.traitsAt(cell.down()).solid()) {
                        return cell;
                    }
                }
            }
        }
        return around;
    }

    /**
     * Bezpečná resurekce: uložená pozice nemusí dnes existovat (zbořená
     * plošina, přeteraformovaný terén) – bot vzkříšený do vzduchu umře pádem
     * dřív, než mu AI stihne pomoct. Bez pevné podlahy (či vodní hladiny na
     * dopad) do 24 bloků pod nohama se přenese na povrch v témže sloupci;
     * lávová „hladina" se jako cíl odmítá – bezpečnější je world spawn.
     */
    private void maybeRescueFloatingSpawn(Vec3 position) {
        String worldName = worldView != null ? worldView.worldName() : null;
        World bukkitWorld = worldName != null ? Bukkit.getWorld(worldName) : null;
        if (bukkitWorld == null) {
            return;
        }
        BlockPos feet = position.toBlockPos();
        bridge.runAt(new Location(bukkitWorld, feet.x(), feet.y(), feet.z()), () -> {
            int bottom = Math.max(bukkitWorld.getMinHeight(), feet.y() - 24);
            for (int y = feet.y() - 1; y >= bottom; y--) {
                var type = bukkitWorld.getBlockAt(feet.x(), y, feet.z()).getType();
                if (type.isSolid() || type == org.bukkit.Material.WATER) {
                    return; // podlaha (nebo voda na dopad) v dosahu – v pořádku
                }
            }
            int surfaceY = bukkitWorld.getHighestBlockYAt(feet.x(), feet.z());
            var surface = bukkitWorld.getBlockAt(feet.x(), surfaceY, feet.z()).getType();
            Location target = surface == org.bukkit.Material.LAVA
                    ? bukkitWorld.getSpawnLocation()
                    : new Location(bukkitWorld, feet.x() + 0.5, surfaceY + 1.0, feet.z() + 0.5);
            LOG.warn("[{}] Resurekce do prázdna na {} (bez podlahy do 24 bloků) – přenos na "
                    + "{} {} {}", name, feet, target.getBlockX(), target.getBlockY(),
                    target.getBlockZ());
            teleport(target);
        });
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
        maybeRescueFloatingSpawn(position);

        // Přílet portálem: první pozice v novém světě je cílový portál –
        // vzpomínka na něj je cesta zpátky domů (Nether: portál↔portál).
        String arrivedFrom = portalArrivedFrom;
        portalArrivedFrom = null;
        if (arrivedFrom != null && worldView != null) {
            var dimension = worldView.dimension();
            if (dimension == dev.botalive.core.world.WorldDimension.OVERWORLD
                    && cameFromEnd(arrivedFrom)) {
                // Návrat z Endu vysazuje u spawnu/postele, kde žádný portál
                // není – slepý zápis by vyrobil fantomový „portál do Endu"
                // u domova (a drby by ho roznesly). Místo toho se oživí
                // vlastní průchodová vzpomínka: kotví cooldown výprav k času
                // návratu a doplní autoritativní anotaci dimenze.
                touchOwnEndPassages(arrivedFrom);
            } else {
                BlockPos arrival = position.toBlockPos();
                java.util.Map<String, String> data = new java.util.HashMap<>();
                data.put("to", arrivedFrom);
                if (dimension == dev.botalive.core.world.WorldDimension.END
                        || dimension == dev.botalive.core.world.WorldDimension.NETHER) {
                    // Anotace dimenze je autoritativní (Bukkit environment /
                    // dimension_type) – heuristika jmen světů ji jen doplňuje.
                    data.put("dim", dimension == dev.botalive.core.world.WorldDimension.END
                            ? "end" : "nether");
                }
                memory.remember(MemoryKind.PORTAL, worldView.worldName(),
                        arrival.x(), arrival.y(), arrival.z(), null,
                        Map.copyOf(data), 0.8);
                if (dimension == dev.botalive.core.world.WorldDimension.END) {
                    // Vstupní straně (průchody s to=tento End svět) doplnit
                    // dim=end – u custom jmen End světů by jinak cooldown
                    // výprav selhal na heuristice názvu.
                    touchOwnEndPassages(worldView.worldName());
                }
            }
        }

        // Životní ambice: vybrat jednou podle povahy a zapamatovat
        // (dračí/netheritový sen jen se zapnutými výpravami).
        if (memory.recall(MemoryKind.AMBITION).isEmpty()) {
            // Fallback nesmí obejít filtr povolených ambicí – bezpečná
            // konstanta místo opakovaného pick().
            var picked = dev.botalive.core.ai.Ambition.ranked(personality).stream()
                    .filter(this::ambitionAllowed)
                    .findFirst()
                    .orElse(dev.botalive.core.ai.Ambition.FULL_IRON);
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
        boolean transition = worldView != null; // false = první spawn/login
        String worldName = resolveWorldName(worldKey);
        try {
            this.worldView = worldViews.view(worldName);
        } catch (IllegalArgumentException e) {
            LOG.error("[{}] Neznámý svět '{}' (klíč {})", name, worldName, worldKey);
            return;
        }
        this.physics = new BotPhysics(worldView, position);
        navigator.world(worldView);
        combat.world(worldView);
        entities.clear();
        if (obstacleTask != null) {
            obstacleTask.cancel(this);
            obstacleTask = null;
        }
        worldView.prefetch(position.toBlockPos(), 2);

        // Disciplína Endu: chůze s pohledem u země (endermani). Průchod
        // portálem do Endu bot okomentuje – je to událost.
        dev.botalive.core.world.WorldDimension dimension = worldView.dimension();
        humanizer.groundGaze(dimension == dev.botalive.core.world.WorldDimension.END);
        if (transition && dimension == dev.botalive.core.world.WorldDimension.END
                && rng.chance(0.7)) {
            chat.sayFrom(PhraseCategory.END_ARRIVE, null);
        }
    }

    /** Očekávaný název světa pro daný protokolový klíč. */
    private String expectedWorldName(String worldKey) {
        return resolveWorldName(worldKey);
    }

    /** Mapování protokolového klíče světa na Bukkit název světa. */
    private String resolveWorldName(String worldKey) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getKey().asString().equals(worldKey)) {
                return world.getName();
            }
        }
        // Nouzový fallback – bot by dostal geometrii cizího světa; hlasitě,
        // ať se to v logu nedá přehlédnout.
        LOG.warn("[{}] Klíč světa '{}' nesedí na žádný načtený svět – fallback na první",
                name, worldKey);
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

    /** Jméno bota do logů – identity hash nikomu nic neřekne. */
    @Override
    public String toString() {
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
                brain.currentGoalId(), role(), connection.connected());
    }

    @Override
    public Personality personality() {
        return personality;
    }

    @Override
    public dev.botalive.api.role.BotRole role() {
        // Vestavěné role se promítnou zpět na enum; cizí role vrací NONE
        // (typ zachovaný kvůli zpětné kompatibilitě – přesné id dá roleId()).
        return dev.botalive.api.role.BotRole.parse(roleId)
                .orElse(dev.botalive.api.role.BotRole.NONE);
    }

    @Override
    public void role(dev.botalive.api.role.BotRole newRole) {
        assignRoleId(newRole == null ? "none"
                : newRole.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public String roleId() {
        return roleId;
    }

    @Override
    public boolean assignRole(String requestedRoleId) {
        if (requestedRoleId == null) {
            return false;
        }
        String norm = requestedRoleId.trim().toLowerCase(java.util.Locale.ROOT);
        if (norm.equals("none")) {
            assignRoleId("none");
            return true;
        }
        if (services.roles().byId(norm).isEmpty()) {
            return false; // neznámá role – bota neměníme
        }
        assignRoleId(norm);
        return true;
    }

    /** Nastaví normalizované id role a persistuje ho. */
    private void assignRoleId(String normalizedId) {
        this.roleId = normalizedId;
        repository.saveRole(id, normalizedId);
    }

    /**
     * Násobič užitečnosti cíle podle profese bota (přes registr rolí – funguje
     * pro vestavěné i cizí role). Volá mozek při rozhodování.
     *
     * @param goalId id cíle
     * @return násobič ({@code 1.0} mimo profil / univerzál)
     */
    public double roleWeight(String goalId) {
        return services.roles().weight(roleId, goalId);
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
    public dev.botalive.api.bot.BotControl control() {
        // Bezstavová fasáda – benigní závod při prvním čtení (nejvýš dvě
        // instance, obě ekvivalentní), proto bez zámku.
        dev.botalive.api.bot.BotControl existing = control;
        if (existing == null) {
            existing = new BotControlImpl(this);
            control = existing;
        }
        return existing;
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
    public dev.botalive.core.container.ContainerTracker containers() {
        return containerTracker;
    }

    @Override
    public dev.botalive.core.container.ContainerClicker clicker() {
        return containerClicker;
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
    public void startGliding(Vec3 look) {
        if (physics == null || physics.gliding()) {
            return;
        }
        actions.startFallFlying();
        physics.startGliding(look);
    }

    @Override
    public void glideSteer(Vec3 look) {
        if (physics != null) {
            physics.glideLook(look);
        }
    }

    @Override
    public void stopGliding() {
        if (physics != null) {
            physics.stopGliding();
        }
    }

    @Override
    public boolean gliding() {
        return physics != null && physics.gliding();
    }

    @Override
    public void startRocketBoost(int ticks) {
        if (physics != null) {
            physics.startRocketBoost(ticks);
        }
    }

    @Override
    public boolean rocketBoosting() {
        return physics != null && physics.rocketBoosting();
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
