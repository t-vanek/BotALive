package dev.botalive.core.pathfinding;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;

/**
 * Vykonavatel cest – převádí naplánovanou {@link Path} na pohybové vstupy.
 *
 * <p>Vlastnosti:</p>
 * <ul>
 *   <li>waypoint se považuje za dosažený s tolerancí (bot nechodí „po pravítku"),</li>
 *   <li>sprint se zapíná na delších rovných úsecích podle povahy bota,</li>
 *   <li>skok se vyvolá, když je další waypoint výš,</li>
 *   <li>mezery 1–3 bloků (naplánované pathfinderem, i diagonální, s dopadem
 *       o blok níž, nebo parkour výskokem na římsu o blok výš) se přeskakují
 *       rozběhem a odrazem na hraně; širší mezera vynucuje sprint,</li>
 *   <li>zavřené dveře v cestě bot otevře interakcí,</li>
 *   <li>detekce zaseknutí: bez postupu několik desítek ticků → požádá o novou cestu,</li>
 *   <li>replanning je asynchronní – bot mezitím dojíždí starou cestu; zahozený
 *       výpočet se kooperativně ruší ({@link PathRequest#cancel()}),</li>
 *   <li>pohyblivý cíl (follow, eskorta) rozpracovanou cestu nezahazuje: malý
 *       posun cíle se jen zapamatuje (stará cesta končí u něj) a plný replán
 *       běží nejvýš jednou za {@link #DRIFT_REPLAN_TICKS},</li>
 *   <li>cesta se periodicky levně validuje proti změnám světa
 *       ({@link PathValidator}) – rozbitý waypoint znamená okamžitý replán,
 *       ne až zaseknutí o 2,5 s později,</li>
 *   <li>daleké cíle (za {@link #FAR_THRESHOLD}) vede hrubý koridor
 *       ({@link FarPlanner} – A* nad povrchovými sondami, počítá se
 *       asynchronně): mezicíle segmentů se berou z koridoru, takže bot
 *       obchází jezera, lávová pole i masivy, na které je přímka krátká;
 *       dokud koridor není spočítaný (a jako fallback), dělí se trasa
 *       postaru podél vzdušné čáry ({@link SegmentPlanner}) s laterálními
 *       posuny a přímým částečným plánem.</li>
 * </ul>
 */
public final class Navigator {

    /** Tolerance dosažení waypointu (bloky, horizontálně). */
    private static final double WAYPOINT_TOLERANCE = 0.45;

    /** Po kolika ticích bez postupu se považujeme za zaseknuté. */
    private static final int STUCK_TICKS = 50;

    /** Posun cíle, který nezneplatní rozpracovanou cestu (bloky²). */
    private static final double DRIFT_KEEP_SQ = 2.0 * 2.0;
    /** Minimální rozestup plných replánů kvůli driftu pohyblivého cíle (ticky). */
    private static final int DRIFT_REPLAN_TICKS = 20;
    /** Perioda levné validace cesty proti změnám světa (ticky). */
    private static final int VALIDATE_TICKS = 10;
    /** Kolik nadcházejících waypointů se validuje. */
    private static final int VALIDATE_WINDOW = 6;

    /** Od jaké vodorovné vzdálenosti se cíl dělí na segmenty (bloky). */
    private static final int FAR_THRESHOLD = 96;
    /** Délka jednoho segmentu po vzdušné čáře (bloky). */
    private static final int SEGMENT_LENGTH = 64;
    /** Dosažení mezicíle (bloky², vodorovně) – pak se plánuje další segment. */
    private static final double SEGMENT_REACHED_SQ = 3 * 3;
    /** Laterální posuny mezicíle při neprůchodnosti (obchůzka slepého ramene). */
    private static final int[] SEGMENT_OFFSETS = {0, 24, -24};

    private final NavigationService service;
    private final DoorOpener actions;
    private final BotRandom rng;
    private final Personality personality;
    /** Osobnostní profil cen cest (deterministický, per bot). */
    private final PathCosts costs;
    /** Dodavatel míst špatných vzpomínek (smrti/nebezpečí) – učení z chyb. */
    private java.util.function.Supplier<java.util.List<BlockPos>> dangerSupplier;

    private WorldView world;
    private Path path;
    private int waypointIndex;
    private BlockPos destination;
    /** Cílový predikát navigace ({@code null} = nenaviguje); destination = jeho kotva. */
    private PathGoal goalSpec;
    /** Mezicíl na dlouhé trase; {@code null} = plánuje se rovnou k cíli. */
    private BlockPos segmentGoal;
    /** Index do {@link #SEGMENT_OFFSETS} při hledání průchodného segmentu. */
    private int segmentAttempt;
    private PathRequest pendingPath;
    /** Hrubý koridor k dalekému cíli (povrchové body); {@code null} = bez koridoru. */
    private java.util.List<BlockPos> corridor;
    /** Index prvního neodbaveného bodu koridoru. */
    private int corridorIndex;
    /** Koridor dosáhl cíle ({@code false} = částečný, na konci se přepočítá). */
    private boolean corridorComplete;
    /** Běžící asynchronní výpočet koridoru. */
    private java.util.concurrent.CompletableFuture<FarPlanner.Corridor> pendingCorridor;
    /** Cíl, ke kterému vede aktuální cesta/výpočet (drift pohyblivých cílů). */
    private BlockPos pathTarget;
    /** Cooldown plných replánů při driftu pohyblivého cíle (ticky). */
    private int driftReplanCooldown;
    /** Cooldown levné validace cesty proti změnám světa (ticky). */
    private int validateCooldown;

    private Vec3 lastProgressPos = Vec3.ZERO;
    private int ticksWithoutProgress;
    private int repathAttempts;
    /** Zbývající ticky „odskakovacího" pokusu při zaseknutí (nízká překážka). */
    private int unstuckJumpTicks;
    /** Cooldown mezi kliky na dveře – server dveře přepíná a stav dorazí se zpožděním. */
    private int doorClickCooldown;

    /** Cache vyhlazení trasy: základní waypoint, výsledek a stáří. */
    private int smoothBase = -1;
    private int smoothIndex;
    private int smoothCooldown;
    /** Sprintuje-li bot po rovince se skoky (rozhodnuto per cesta dle povahy). */
    private boolean sprintHopper;

    /** Kolikrát smí bot cestu „odblokovat" zásahem do terénu, než to vzdá. */
    private static final int MAX_ASSIST_CYCLES = 10;
    private boolean assistNeeded;
    private int assistCycles;

    /** Strop položených bloků na jeden plán (zrcadlí strop BridgeTasku). */
    private static final int MAX_PLAN_PLACEMENTS = 12;
    /** Strop žebříkových příček na jeden plán (zrcadlí strop LadderTasku). */
    private static final int MAX_PLAN_LADDERS = 8;

    /** Zásahy do terénu povolené konfigurací bota ({@code ai.terraforming}). */
    private boolean terraformingAllowed;
    /** Dodavatel počtu stavebních bloků v inventáři (rozpočet pokládání). */
    private java.util.function.IntSupplier placeBudget = () -> 0;
    /** Dodavatel počtu žebříků v inventáři (rozpočet žebříkových hran). */
    private java.util.function.IntSupplier ladderBudget = () -> 0;
    /** Aktuální navigace už plánuje s kopacími hranami (po selhání pěšího plánu). */
    private boolean actionsEnabled;
    /** Zásah čekající na vykonání (bot stojí u zablokovaného waypointu). */
    private TerrainAction pendingAction;
    /** Index waypointu čekajícího zásahu. */
    private int pendingActionIndex = -1;
    /** Indexy už vykonaných zásahů aktuální cesty. */
    private final java.util.Set<Integer> resolvedActions = new java.util.HashSet<>();

    /**
     * @param service     asynchronní pathfinding
     * @param actions     otevírání dveří v cestě ({@code null} = bez interakcí)
     * @param rng         per-bot náhoda
     * @param personality osobnost (sprint, trpělivost)
     */
    public Navigator(NavigationService service, DoorOpener actions, BotRandom rng, Personality personality) {
        this.service = service;
        this.actions = actions;
        this.rng = rng;
        this.personality = personality;
        this.costs = PathCosts.of(personality);
    }

    /**
     * Nastaví aktivní svět navigace (po spawnu/změně světa).
     *
     * @param world pohled na svět
     */
    public void world(WorldView world) {
        this.world = world;
        stop();
    }

    /**
     * Nastaví zdroj špatných vzpomínek (paměť DEATH/DANGER aktuálního světa);
     * cesty se jim pak vyhýbají – bot se učí z vlastních chyb.
     *
     * @param supplier dodavatel míst (může vracet prázdný seznam)
     */
    public void dangerSupplier(java.util.function.Supplier<java.util.List<BlockPos>> supplier) {
        this.dangerSupplier = supplier;
    }

    /**
     * Povolí kopací hrany v plánu ({@code ai.terraforming}) – po selhání
     * pěšího plánu se cesta přepočítá se zásahy do terénu, místo aby rovnou
     * eskalovala k reaktivnímu assistu.
     *
     * @param allowed zásahy do terénu povoleny
     */
    public void terraforming(boolean allowed) {
        this.terraformingAllowed = allowed;
    }

    /**
     * Nastaví dodavatele počtu stavebních bloků v inventáři – plán s akcemi
     * nikdy neslíbí víc položených bloků (mosty, pilíře), než bot má.
     *
     * @param supplier počet dostupných stavebních bloků
     */
    public void placeBudget(java.util.function.IntSupplier supplier) {
        this.placeBudget = supplier;
    }

    /**
     * Nastaví dodavatele počtu žebříků v inventáři – plán s akcemi nikdy
     * neslíbí vyšší žebříkový výstup, než na kolik má bot příček.
     *
     * @param supplier počet dostupných žebříků
     */
    public void ladderBudget(java.util.function.IntSupplier supplier) {
        this.ladderBudget = supplier;
    }

    /**
     * @return zásah do terénu, u kterého bot stojí a čeká na vykonání
     *         (vykopání bloků z plánu), nebo {@code null}
     */
    public TerrainAction actionNeeded() {
        return pendingAction;
    }

    /** Zásah z plánu je vykonaný – pokračovat po téže cestě (žádný replán). */
    public void actionResolved() {
        if (pendingActionIndex >= 0) {
            resolvedActions.add(pendingActionIndex);
        }
        pendingAction = null;
        pendingActionIndex = -1;
        ticksWithoutProgress = 0;
    }

    /**
     * Zahájí navigaci k cíli. Výpočet je asynchronní; do té doby bot stojí
     * (nebo dokončuje předchozí cestu, pokud vede stejným směrem).
     *
     * @param from aktuální pozice bota
     * @param to   cílový blok
     */
    public void navigateTo(Vec3 from, BlockPos to) {
        navigateTo(from, PathGoal.block(to));
    }

    /**
     * Zahájí navigaci k obecnému cíli ({@link PathGoal} – okruh kolem bloku,
     * útěk od hrozby, výšková hladina, nejbližší z kandidátů). Dálková
     * segmentace a prefetch se řídí kotvou cíle ({@link PathGoal#anchor()}).
     *
     * @param from aktuální pozice bota
     * @param goal cílový predikát
     */
    public void navigateTo(Vec3 from, PathGoal goal) {
        if (world == null) {
            return;
        }
        BlockPos to = goal.anchor();
        if (goal.equals(goalSpec) && (hasPath() || pendingPath != null || assistNeeded)) {
            return; // už tam jdeme (nebo čekáme na odblokování terénu)
        }
        // Pohyblivý cíl (follow, eskorta, útěk před pohybující se hrozbou):
        // malý posun kotvy u cíle stejného tvaru rozpracovanou cestu
        // nezahazuje – stará končí pár bloků od nového cíle a dojede se;
        // plný replán se pouští nejvýš jednou za {@link #DRIFT_REPLAN_TICKS}.
        // Bez throttlu by sledování spouštělo plný A* při každém kroku cíle.
        if (goalSpec != null && goal.sameShape(goalSpec)
                && segmentGoal == null && !assistNeeded
                && (hasPath() || pendingPath != null)
                && !isFar(from.toBlockPos(), to)) {
            destination = to;
            goalSpec = goal;
            double driftSq = pathTarget == null
                    ? Double.MAX_VALUE : to.distanceSquared(pathTarget);
            if (driftSq <= DRIFT_KEEP_SQ) {
                return; // konec cesty je pořád u cíle – dojet
            }
            if (driftReplanCooldown > 0) {
                return; // replán počká; bot zatím dojíždí starou cestu
            }
            driftReplanCooldown = DRIFT_REPLAN_TICKS;
            repathAttempts = 0;
            requestPath(from);
            return;
        }
        destination = to;
        goalSpec = goal;
        repathAttempts = 0;
        assistNeeded = false;
        assistCycles = 0;
        segmentGoal = null;
        segmentAttempt = 0;
        corridor = null;
        corridorIndex = 0;
        pendingCorridor = null;
        if (isFar(from.toBlockPos(), to)) {
            requestCorridor(from.toBlockPos());
            advanceSegment(from.toBlockPos());
        }
        requestPath(from);
    }

    /** Zruší navigaci (včetně kooperativního zrušení běžícího výpočtu). */
    public void stop() {
        cancelPending();
        path = null;
        waypointIndex = 0;
        destination = null;
        goalSpec = null;
        segmentGoal = null;
        segmentAttempt = 0;
        corridor = null;
        corridorIndex = 0;
        pendingCorridor = null;
        pathTarget = null;
        driftReplanCooldown = 0;
        ticksWithoutProgress = 0;
        assistNeeded = false;
        assistCycles = 0;
        actionsEnabled = false;
        pendingAction = null;
        pendingActionIndex = -1;
        resolvedActions.clear();
    }

    /** Zruší běžící výpočet cesty (pool přestane mlít mrtvou práci). */
    private void cancelPending() {
        if (pendingPath != null) {
            pendingPath.cancel();
            pendingPath = null;
        }
    }

    /**
     * @return {@code true} když navigace narazila (nedosažitelný cíl nebo tvrdé
     *         zaseknutí) a čeká na zásah do terénu – prokopání/přemostění
     */
    public boolean needsAssist() {
        return assistNeeded;
    }

    /**
     * Zásah do terénu proběhl – zkusit cestu znovu.
     *
     * @param from aktuální pozice bota
     */
    public void assistResolved(Vec3 from) {
        assistNeeded = false;
        assistCycles++;
        repathAttempts = 0;
        path = null;
        requestPath(from);
    }

    /** Zásah do terénu není možný – navigaci vzdát (cíl si vybere jinak). */
    public void assistFailed() {
        assistNeeded = false;
        stop();
    }

    /** @return {@code true} pokud má bot rozpracovanou cestu */
    public boolean hasPath() {
        return path != null && waypointIndex < path.waypoints().size();
    }

    /** @return {@code true} pokud navigace směřuje k cíli (i když se cesta teprve počítá) */
    public boolean navigating() {
        return destination != null;
    }

    /** @return cílový blok navigace, nebo {@code null} */
    public BlockPos destination() {
        return destination;
    }

    /**
     * @return aktuální objekt zájmu navigace: mezicíl segmentu na dlouhé
     *         trase, jinak konečný cíl (pro směr zásahů do terénu, lodě…)
     */
    public BlockPos currentObjective() {
        return segmentGoal != null ? segmentGoal : destination;
    }

    /** Je cíl dál než {@link #FAR_THRESHOLD} (vodorovně)? */
    private static boolean isFar(BlockPos from, BlockPos to) {
        long dx = from.x() - to.x();
        long dz = from.z() - to.z();
        return dx * dx + dz * dz > (long) FAR_THRESHOLD * FAR_THRESHOLD;
    }

    /**
     * Vybere další mezicíl a přednačte okolí trasy k němu. Preferuje bod
     * hrubého koridoru ({@link FarPlanner}); bez koridoru (nebo když je
     * vyčerpaný/mimo dosah) spadne na přímkovou segmentaci s laterálními
     * posuny.
     *
     * @param from odkud se plánuje
     * @return {@code false} když segment nejde vybrat (blízko cíle, neznámá
     *         oblast po všech posunech) – plánuje se rovnou k cíli
     */
    private boolean advanceSegment(BlockPos from) {
        segmentGoal = null;
        if (destination == null || !isFar(from, destination)) {
            return false;
        }
        if (corridor != null) {
            BlockPos viaCorridor = nextCorridorGoal(from);
            if (viaCorridor != null) {
                segmentGoal = viaCorridor;
                world.prefetch(viaCorridor, 2);
                world.prefetch(new BlockPos((from.x() + viaCorridor.x()) / 2, from.y(),
                        (from.z() + viaCorridor.z()) / 2), 2);
                return true;
            }
        }
        while (segmentAttempt < SEGMENT_OFFSETS.length) {
            BlockPos segment = SegmentPlanner.nextSegment(world, from, destination,
                    SEGMENT_LENGTH, SEGMENT_OFFSETS[segmentAttempt]);
            if (segment != null) {
                segmentGoal = segment;
                // Prefetch koridoru: mezicíl a střed úseku k němu.
                world.prefetch(segment, 2);
                world.prefetch(new BlockPos((from.x() + segment.x()) / 2, from.y(),
                        (from.z() + segment.z()) / 2), 2);
                return true;
            }
            segmentAttempt++;
        }
        return false;
    }

    /**
     * Mezicíl z hrubého koridoru: nejvzdálenější bod souvislého úseku
     * v dosahu {@link #SEGMENT_LENGTH}. Souvislost je klíčová – koridor
     * obcházející jezero se může do dosahu vrátit až na protějším břehu
     * a skok na pozdější bod by obchůzku zkratoval přes vodu.
     * {@code segmentAttempt > 0} (mezicíl nevyšel) posouvá výběr o bod dál –
     * vadný bod se přeskočí. Bod se před použitím znovu ověří povrchovou
     * sondou (svět se od výpočtu koridoru mohl načíst/změnit); nenačtený
     * bod se použije optimisticky, jak je (prefetch už běží).
     *
     * @param from odkud se plánuje
     * @return mezicíl, nebo {@code null} – koridor vyčerpaný/mimo dosah
     *         (volající spadne na přímkovou segmentaci)
     */
    private BlockPos nextCorridorGoal(BlockPos from) {
        int best = -1;
        for (int i = corridorIndex; i < corridor.size(); i++) {
            long dx = corridor.get(i).x() - from.x();
            long dz = corridor.get(i).z() - from.z();
            if (dx * dx + dz * dz <= (long) SEGMENT_LENGTH * SEGMENT_LENGTH) {
                best = i;
            } else if (best >= 0) {
                break; // konec souvislého úseku v dosahu
            }
        }
        if (best < 0) {
            return null;
        }
        int target = Math.min(best + segmentAttempt, corridor.size() - 1);
        corridorIndex = target;
        BlockPos raw = corridor.get(target);
        if (target >= corridor.size() - 1 && !corridorComplete) {
            // Částečný koridor dojel na konec a cíl je pořád daleko –
            // přepočítat z aktuální pozice (poslední bod ještě poslouží
            // jako mezicíl, než nový koridor dorazí).
            corridor = null;
            requestCorridor(from);
        }
        BlockPos resolved = SegmentPlanner.surfaceAt(world, raw.x(), raw.y(), raw.z());
        return resolved != null ? resolved : raw;
    }

    /** Požádá o asynchronní výpočet hrubého koridoru k cíli. */
    private void requestCorridor(BlockPos from) {
        if (!service.farCorridors() || world == null || destination == null) {
            return;
        }
        pendingCorridor = service.planCorridor(world, from, destination);
    }

    /**
     * Jeden tick navigace.
     *
     * @param position aktuální pozice bota (nohy)
     * @param onGround bot na zemi
     * @param inWater  bot je ve vodě (plavecký režim: svislý pohyb skokem)
     * @return pohybový vstup pro fyziku (IDLE pokud není kam jít)
     */
    public MoveInput tick(Vec3 position, boolean onGround, boolean inWater) {
        if (driftReplanCooldown > 0) {
            driftReplanCooldown--;
        }
        // Během čekání na zásah do terénu bot stojí.
        if (assistNeeded) {
            return MoveInput.IDLE;
        }
        // Vyzvednout dopočítaný hrubý koridor a přesměrovat na něj rozjetou
        // segmentaci – přímkový mezicíl mohl mířit do slepého ramene.
        if (pendingCorridor != null && pendingCorridor.isDone()) {
            FarPlanner.Corridor computed = pendingCorridor.join();
            pendingCorridor = null;
            if (!computed.isEmpty() && destination != null) {
                corridor = computed.points();
                corridorIndex = 0;
                corridorComplete = computed.complete();
                org.slf4j.LoggerFactory.getLogger(Navigator.class).debug(
                        "[nav] koridor: {} bodů, complete={}, cíl={}",
                        corridor.size(), corridorComplete, destination);
                BlockPos feet = position.toBlockPos();
                if (isFar(feet, destination)) {
                    segmentAttempt = 0;
                    advanceSegment(feet);
                    requestPath(position);
                }
            }
        }
        // Vyzvednout dopočítanou cestu.
        if (pendingPath != null && pendingPath.isDone()) {
            Path computed = pendingPath.join();
            pendingPath = null;
            org.slf4j.LoggerFactory.getLogger(Navigator.class).debug(
                    "[nav] cesta: {} waypointů, complete={}, cíl={}, segment={}",
                    computed.waypoints().size(), computed.complete(), destination, segmentGoal);
            if (!computed.isEmpty()) {
                path = computed;
                waypointIndex = 0;
                smoothBase = -1;
                validateCooldown = VALIDATE_TICKS;
                resolvedActions.clear();
                pendingAction = null;
                pendingActionIndex = -1;
                // Sprint-skoky na rovinkách: odvážní a čilí boti si na cestu
                // „zabunnyhopují", líní chodí. Rozhodnutí drží celou cestu.
                sprintHopper = rng.next() < 0.25
                        + 0.45 * personality.trait(Trait.COURAGE)
                        - 0.35 * personality.trait(Trait.LAZINESS);
            } else if (destination != null) {
                if (segmentGoal != null) {
                    // Mezicíl nedosažitelný → laterální posun, nakonec přímý
                    // (částečný) plán ke konečnému cíli.
                    segmentAttempt++;
                    advanceSegment(position.toBlockPos());
                    requestPath(position);
                    return MoveInput.IDLE;
                }
                // Cíl je pěšky nedosažitelný – nejdřív zkusit plán s kopacími
                // hranami (souvislý tunel místo reaktivní eskalace), teprve
                // pak assist (mosty přes tekutiny, pilíře, žebříky).
                if (tryEnableActions(position)) {
                    return MoveInput.IDLE;
                }
                if (assistCycles < MAX_ASSIST_CYCLES) {
                    assistNeeded = true;
                    return MoveInput.IDLE;
                }
                stop();
            }
        }
        if (!hasPath()) {
            if (destination != null && pendingPath == null && path != null) {
                stop(); // cesta dokončena
            }
            return MoveInput.IDLE;
        }

        // Levná validace: svět se pod naplánovanou cestou mění (výbuch,
        // vykopnutý blok, postavená zeď). Rozbitý waypoint → okamžitý replán,
        // ne až po fyzickém zaseknutí o 2,5 s později. Waypointy s dosud
        // nevykonaným zásahem do terénu jsou zablokované ZÁMĚRNĚ – přeskočit.
        if (--validateCooldown <= 0) {
            validateCooldown = VALIDATE_TICKS;
            int limit = Math.min(waypointIndex + VALIDATE_WINDOW, path.waypoints().size() - 1);
            for (int i = waypointIndex; i <= limit; i++) {
                if (path.actions().containsKey(i) && !resolvedActions.contains(i)) {
                    continue;
                }
                if (PathValidator.blocked(world, path.waypoints().get(i))) {
                    org.slf4j.LoggerFactory.getLogger(Navigator.class).debug(
                            "[nav] cesta rozbitá u {} – replán", path.waypoints().get(i));
                    path = null;
                    requestPath(position);
                    return MoveInput.IDLE;
                }
            }
        }

        BlockPos waypoint = path.waypoints().get(waypointIndex);
        Vec3 target = waypoint.center();
        Vec3 delta = target.sub(position);

        // Zásah do terénu z plánu: aktuální waypoint je zablokovaný bloky,
        // které se mají vykopat. Dojít na dosah, ohlásit zásah (vykonají ho
        // tasky přes {@link #actionNeeded()}) a stát – po {@link #actionResolved()}
        // cesta pokračuje bez replánu.
        TerrainAction action = path.actions().get(waypointIndex);
        if (action != null && !resolvedActions.contains(waypointIndex)) {
            // Žebříkový waypoint leží NAD botem (vršek stěny): svislé okno se
            // roztahuje o výšku stěny a vodorovné se naopak utahuje – LadderTask
            // odvozuje sloupec příček od živé pozice, bot musí stát na patě
            // (waypoint před akcí), ne o dvě buňky dřív.
            double verticalWindow = action.ladder() != null
                    ? action.ladder().height() + 1.5 : 2.5;
            double horizontalWindow = action.ladder() != null ? 1.5 : 3.0;
            if (delta.horizontalLength() < horizontalWindow
                    && Math.abs(delta.y()) < verticalWindow) {
                pendingAction = action;
                pendingActionIndex = waypointIndex;
                return MoveInput.IDLE;
            }
        }

        // Waypoint dosažen? Ve vodě je svislá tolerance při stoupání volnější
        // (plave se šikmo) – ale při klesání těsná: potápěcí waypointy odbavené
        // z dálky by cestu „dokončily" vysoko nad hlubokým cílem a záchranný
        // reflex by bota vynesl zpátky k hladině.
        double verticalTolerance = inWater ? (delta.y() < 0 ? 0.6 : 1.6) : 1.2;
        if (delta.horizontalLength() < WAYPOINT_TOLERANCE
                && Math.abs(delta.y()) < verticalTolerance) {
            waypointIndex++;
            if (waypointIndex >= path.waypoints().size()) {
                BlockPos feet = position.toBlockPos();
                if (segmentGoal != null && feet.distanceSquared(segmentGoal) <= SEGMENT_REACHED_SQ) {
                    // Mezicíl dosažen → naplánovat další segment trasy.
                    segmentAttempt = 0;
                    advanceSegment(feet);
                    requestPath(position);
                } else if (!path.complete() && destination != null
                        && (goalSpec == null || !goalSpec.reached(feet))
                        && currentObjective() != null
                        && feet.distanceSquared(currentObjective()) > 4) {
                    requestPath(position); // částečná cesta – doplánovat
                } else if (segmentGoal != null && destination != null) {
                    // Kompletní cesta skončila kus od mezicíle (normalizace
                    // cíle nad deskou apod.) → pokračovat dalším segmentem.
                    segmentAttempt = 0;
                    advanceSegment(feet);
                    requestPath(position);
                } else if (destination != null && goalSpec != null
                        && !goalSpec.reached(feet)
                        && feet.distanceSquared(destination) > 4) {
                    // Cíl mezitím poodešel (drift pohyblivého cíle) – cesta
                    // dojetá u staré kotvy, ale aktuální predikát nesplněný
                    // → doplánovat zbytek. Vzdálenostní pojistka drží stop
                    // u blokových cílů normalizovaných o buňku níž.
                    requestPath(position);
                } else {
                    stop();
                }
                return MoveInput.IDLE;
            }
            waypoint = path.waypoints().get(waypointIndex);
            target = waypoint.center();
            delta = target.sub(position);
        }

        detectStuck(position);

        // Zavřené dveře před nosem → otevřít. Traits znají stav dveří, takže
        // klik padne jen na zavřené; cooldown kryje zpoždění block-update
        // paketu, aby bot dveře omylem nepřepínal tam a zpět.
        if (doorClickCooldown > 0) {
            doorClickCooldown--;
        }
        if (world.traitsAt(waypoint).door() && doorClickCooldown == 0 && actions != null) {
            actions.open(waypoint);
            doorClickCooldown = 8;
        }

        // Sestup po žebříku/liáně: bot visí na šplhatelném bloku a waypoint je
        // pod ním. Vodorovný vstup by fyzika brala jako „šplhej vzhůru" (vanilla:
        // tlak do stěny = stoupání) a driftoval by bota pryč od cíle – správný
        // sestup je pustit se a nechat ho sjíždět (−0.15/tick). Podmínka na
        // vodorovnou odchylku drží zásah jen nad kolmým waypointem.
        if (!inWater && delta.y() < -0.6 && delta.horizontalLength() < 0.6
                && world.traitsAt(position.toBlockPos()).climbable()) {
            return MoveInput.IDLE;
        }

        boolean jump;
        if (inWater) {
            // Plavání: skok = stoupání. Drž ho, když je waypoint na úrovni či výš,
            // nebo když má bot hlavu pod vodou – jinak by se potopil a utopil.
            // Naplánovaný sestup (waypoint výrazně níž) ale skok pouští i pod
            // hladinou: potápěcí úseky cesty (dno jámy, zatopený tunel) by jinak
            // nešly vykonat a bot by věčně šlapal vodu u hladiny. O dech se
            // stará LiquidReflex – při docházejícím vzduchu přebíjí vše vzhůru.
            boolean submerged = world.traitsAt(position.toBlockPos().up()).liquid();
            boolean divePlanned = delta.y() < -0.6;
            jump = (delta.y() > -0.4 || submerged) && !divePlanned;
        } else {
            // Po schodech se neskáče – step-up fyzika je vyjde plynule.
            boolean stairsAhead = world.traitsAt(waypoint.down()).stepFriendly();
            jump = delta.y() > 0.5 && onGround && !stairsAhead;
            if (unstuckJumpTicks > 0) {
                unstuckJumpTicks--;
                // Odskočit nízkou překážku (deska, koberec…) – ale NIKDY na hraně
                // srázu: slepý odskok u okraje kaverny posílá boty do hlubin
                // (dva mrtví průzkumníci na stejném útesu během provozního testu).
                if (!cliffAhead(position, delta.horizontal().normalized())) {
                    jump = jump || onGround;
                }
            }
        }
        Vec3 direction = delta.horizontal().normalized();

        // Skok přes mezeru: pathfinder generuje dlouhé segmenty (waypointy
        // vzdálené ≥ 2 bloky) JEN pro naplánované přeskoky – rozestup vůči
        // předchozímu waypointu je proto spolehlivý rozpoznávací znak. Samotná
        // vzdálenost bota od waypointu nestačí: waypoint „spadni do jámy
        // o buňku dál" by se z protější hrany tvářil stejně a bot by jámu
        // sprint-skokem přeskakoval tam a zpět. Rozběh sprintem a odraz přesně
        // na hraně – u širší mezery je sprint nutný, aby doletěl.
        BlockPos gapOrigin = waypointIndex > 0
                ? path.waypoints().get(waypointIndex - 1) : position.toBlockPos();
        boolean plannedGap = Math.abs(waypoint.x() - gapOrigin.x()) > 1
                || Math.abs(waypoint.z() - gapOrigin.z()) > 1;
        // Svislé meze kryjí i dopad o blok níž (−0.5) a parkour výskok na
        // římsu o blok výš (+1.5) – rozestup waypointů zaručuje, že jde
        // o naplánovaný skok, ne o běžný krok.
        boolean gapSegment = plannedGap && onGround && !inWater
                && delta.y() > -1.6 && delta.y() < 1.7
                && delta.horizontalLength() > 1.4;
        if (gapSegment) {
            boolean wideGap = delta.horizontalLength() > 2.2;
            if (takeoffEdge(position, direction)) {
                return new MoveInput(direction, wideGap, true, false);
            }
            return new MoveInput(direction, wideGap, false, false);
        }

        // Vyhlazení trasy: na rovině mířit na nejvzdálenější „viditelný"
        // waypoint – rohy se řežou po pochozí ploše, chůze dostane oblouky.
        int smooth = smoothedTarget(position);
        if (smooth > waypointIndex && !inWater) {
            direction = path.waypoints().get(smooth).center().sub(position)
                    .horizontal().normalized();
            // Rohy, kolem kterých bot zkratkou prošel, odbavit. Blízkost
            // nestačí: diagonální zkratka nechá rohový waypoint stranou dál
            // než toleranci a okno vyhlazení by se na něm zaseklo – waypoint
            // je odbavený i tehdy, když vůči směru zkratky přestal být před
            // botem (projekce ≤ ~0.35 bloku).
            while (waypointIndex < smooth) {
                Vec3 toWaypoint = path.waypoints().get(waypointIndex).center()
                        .sub(position).horizontal();
                boolean close = toWaypoint.horizontalLength() < 1.3;
                boolean behind = toWaypoint.x() * direction.x()
                        + toWaypoint.z() * direction.z() < 0.35;
                if (!close && !behind) {
                    break;
                }
                waypointIndex++;
            }
        }

        // Hrana srázu: před botem zeje díra hlubší než bezpečný seskok a cesta
        // dolů nevede → přibrzdit, ať setrvačnost nepřenese bota přes okraj.
        if (onGround && !inWater && !jump && delta.y() > -0.5 && cliffAhead(position, direction)) {
            return new MoveInput(direction.mul(0.25), false, false, false);
        }

        boolean sprint = !inWater && shouldSprint(delta);
        // Sprint-skoky: dlouhá volná rovinka před botem → bunny hop (rychlejší
        // přesun, lidský návyk). Jen u povah, které si to na cestu „zapnuly".
        if (sprint && sprintHopper && onGround && smooth - waypointIndex >= 3
                && Math.abs(delta.y()) < 0.5) {
            jump = true;
        }
        return MoveInput.of(direction, sprint, jump);
    }

    /** Vyhlazený cílový waypoint (cache – přepočet při postupu nebo po pár ticích). */
    private int smoothedTarget(Vec3 position) {
        if (world == null || !hasPath()) {
            return waypointIndex;
        }
        if (smoothBase == waypointIndex && smoothCooldown-- > 0) {
            return Math.min(smoothIndex, path.waypoints().size() - 1);
        }
        smoothBase = waypointIndex;
        smoothCooldown = 4;
        smoothIndex = PathSmoother.smoothTarget(world, position, path.waypoints(), waypointIndex);
        return smoothIndex;
    }

    /** Odrazová hrana: kousek před botem už chybí pevná podlaha pod nohama. */
    private boolean takeoffEdge(Vec3 position, Vec3 direction) {
        BlockPos ahead = position.add(direction.mul(0.7)).toBlockPos();
        return !world.traitsAt(ahead.down()).solid();
    }

    /**
     * Sloupec kousek před botem nemá do 4 bloků pod nohama bezpečnou podlahu.
     * Sdílená sonda s {@link dev.botalive.core.physics.EdgeGuard} – na rozdíl
     * od původní verze nebere lávu jako „podlahu", takže bot brzdí i před
     * hranou lávového jezera.
     */
    private boolean cliffAhead(Vec3 position, Vec3 direction) {
        return !dev.botalive.core.physics.EdgeGuard.safeAhead(world, position, direction, 4);
    }

    /** Sprint na delších úsecích; líní boti sprintují méně. */
    private boolean shouldSprint(Vec3 delta) {
        if (!hasPath()) {
            return false;
        }
        int remaining = path.waypoints().size() - waypointIndex;
        double laziness = personality.trait(Trait.LAZINESS);
        return remaining > 6 && delta.horizontalLength() > 0.8 && rng.next() > laziness * 0.4;
    }

    /** Detekce zaseknutí a asynchronní replanning. */
    private void detectStuck(Vec3 position) {
        if (position.distanceSquared(lastProgressPos) > 0.04) {
            lastProgressPos = position;
            ticksWithoutProgress = 0;
            return;
        }
        // V půlce čekání zkusit odskočit – nízké překážky (deska, plot u země,
        // hrana koberce), které step-up nezvládl. Levné a často stačí.
        if (++ticksWithoutProgress == STUCK_TICKS / 2) {
            unstuckJumpTicks = 6;
        }
        if (ticksWithoutProgress < STUCK_TICKS) {
            return;
        }
        ticksWithoutProgress = 0;
        if (destination != null && repathAttempts < 3) {
            repathAttempts++;
            path = null;
            requestPath(position);
        } else if (destination != null && tryEnableActions(position)) {
            // Replany nepomohly – přepočítat s kopacími hranami (souvislý
            // tunel), teprve pak reaktivní assist.
            path = null;
        } else if (destination != null && assistCycles < MAX_ASSIST_CYCLES) {
            // Překážka chce zásah, který plán neumí (most, pilíř, žebřík).
            assistNeeded = true;
            path = null;
        } else {
            stop(); // vzdáváme to – cíl si vybere něco jiného
        }
    }

    /**
     * Povolí kopací hrany pro tuto navigaci a vyžádá přepočet – jen jednou
     * (další selhání už jde do assistu) a jen s povoleným terraformingem
     * a zapnutým kill-switchem.
     *
     * @param position aktuální pozice bota
     * @return {@code true} když se přepočet se zásahy rozjel
     */
    private boolean tryEnableActions(Vec3 position) {
        if (actionsEnabled || !terraformingAllowed || !service.plannedActions()) {
            return false;
        }
        actionsEnabled = true;
        repathAttempts = 0;
        requestPath(position);
        return true;
    }

    private void requestPath(Vec3 from) {
        BlockPos target = currentObjective();
        if (world == null || target == null) {
            return;
        }
        cancelPending();
        java.util.List<BlockPos> dangers = dangerSupplier != null
                ? dangerSupplier.get() : java.util.List.of();
        pathTarget = target;
        // Mezicíle segmentů jsou vždy konkrétní bloky; přímý plán jde na
        // cílový predikát (okruh, útěk, hladina, kandidáti…).
        PathGoal requestGoal = segmentGoal != null || goalSpec == null
                ? PathGoal.block(target) : goalSpec;
        PathOptions options = actionsEnabled
                ? PathOptions.withActions(
                        Math.min(MAX_PLAN_PLACEMENTS, placeBudget.getAsInt()),
                        Math.min(MAX_PLAN_LADDERS, ladderBudget.getAsInt()))
                : PathOptions.WALK_ONLY;
        pendingPath = service.request(world, from.toBlockPos(), requestGoal, 0, dangers,
                costs, options);
    }

    /**
     * Snímek stavu navigace pro diagnostiku ({@code /botalive path}) a testy.
     * Čte se bez zámků z cizího vlákna – hodnoty jsou orientační.
     *
     * @param destination   konečný cíl navigace ({@code null} = nenaviguje)
     * @param segmentGoal   mezicíl dlouhé trasy ({@code null} = přímý plán)
     * @param computing     běží výpočet cesty
     * @param assistNeeded  čeká se na zásah do terénu
     * @param pathComplete  aktuální cesta vede až k cíli (ne částečná)
     * @param waypointIndex index aktuálního waypointu
     * @param waypointCount počet waypointů cesty
     * @param upcoming      několik nejbližších waypointů (max 5)
     * @param corridorIndex index aktuálního bodu hrubého koridoru
     * @param corridorCount počet bodů hrubého koridoru (0 = bez koridoru)
     */
    public record DebugSnapshot(BlockPos destination, BlockPos segmentGoal,
                                boolean computing, boolean assistNeeded, boolean pathComplete,
                                int waypointIndex, int waypointCount,
                                java.util.List<BlockPos> upcoming,
                                int corridorIndex, int corridorCount) {
    }

    /** @return snímek stavu navigace (orientační, bez zámků) */
    public DebugSnapshot debugSnapshot() {
        Path p = path;
        int idx = waypointIndex;
        int count = 0;
        boolean complete = false;
        java.util.List<BlockPos> upcoming = java.util.List.of();
        if (p != null) {
            count = p.waypoints().size();
            complete = p.complete();
            if (idx >= 0 && idx < count) {
                upcoming = java.util.List.copyOf(
                        p.waypoints().subList(idx, Math.min(idx + 5, count)));
            }
        }
        java.util.List<BlockPos> c = corridor;
        return new DebugSnapshot(destination, segmentGoal, pendingPath != null,
                assistNeeded, complete, idx, count, upcoming,
                corridorIndex, c != null ? c.size() : 0);
    }
}
