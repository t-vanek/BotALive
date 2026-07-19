package dev.botalive.core.pathfinding;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.network.BotActions;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

/**
 * Vykonavatel cest – převádí naplánovanou {@link Path} na pohybové vstupy.
 *
 * <p>Vlastnosti:</p>
 * <ul>
 *   <li>waypoint se považuje za dosažený s tolerancí (bot nechodí „po pravítku"),</li>
 *   <li>sprint se zapíná na delších rovných úsecích podle povahy bota,</li>
 *   <li>skok se vyvolá, když je další waypoint výš,</li>
 *   <li>mezery 1–2 bloků (naplánované pathfinderem, i diagonální nebo
 *       s dopadem o blok níž) se přeskakují rozběhem a odrazem na hraně;
 *       širší mezera vynucuje sprint,</li>
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
 *   <li>daleké cíle (za {@link #FAR_THRESHOLD}) se dělí na segmenty podél
 *       vzdušné čáry ({@link SegmentPlanner}) s prefetchem chunků po trase;
 *       neprůchodný segment zkouší laterální posuny, pak přímý částečný plán.</li>
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
    private final BotActions actions;
    private final BotRandom rng;
    private final Personality personality;
    /** Dodavatel míst špatných vzpomínek (smrti/nebezpečí) – učení z chyb. */
    private java.util.function.Supplier<java.util.List<BlockPos>> dangerSupplier;

    private WorldView world;
    private Path path;
    private int waypointIndex;
    private BlockPos destination;
    /** Mezicíl na dlouhé trase; {@code null} = plánuje se rovnou k cíli. */
    private BlockPos segmentGoal;
    /** Index do {@link #SEGMENT_OFFSETS} při hledání průchodného segmentu. */
    private int segmentAttempt;
    private PathRequest pendingPath;
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

    /**
     * @param service     asynchronní pathfinding
     * @param actions     akční primitivy (otevírání dveří)
     * @param rng         per-bot náhoda
     * @param personality osobnost (sprint, trpělivost)
     */
    public Navigator(NavigationService service, BotActions actions, BotRandom rng, Personality personality) {
        this.service = service;
        this.actions = actions;
        this.rng = rng;
        this.personality = personality;
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
     * Zahájí navigaci k cíli. Výpočet je asynchronní; do té doby bot stojí
     * (nebo dokončuje předchozí cestu, pokud vede stejným směrem).
     *
     * @param from aktuální pozice bota
     * @param to   cílový blok
     */
    public void navigateTo(Vec3 from, BlockPos to) {
        if (world == null) {
            return;
        }
        if (to.equals(destination) && (hasPath() || pendingPath != null || assistNeeded)) {
            return; // už tam jdeme (nebo čekáme na odblokování terénu)
        }
        // Pohyblivý cíl (follow, eskorta, přiblížení k entitě): malý posun cíle
        // rozpracovanou cestu nezahazuje – stará končí pár bloků od nového cíle
        // a dojede se; plný replán se pouští nejvýš jednou za
        // {@link #DRIFT_REPLAN_TICKS}. Bez throttlu by sledování spouštělo
        // plný A* při každém kroku cíle o blok.
        if (destination != null && segmentGoal == null && !assistNeeded
                && (hasPath() || pendingPath != null)
                && !isFar(from.toBlockPos(), to)) {
            destination = to;
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
        repathAttempts = 0;
        assistNeeded = false;
        assistCycles = 0;
        segmentGoal = null;
        segmentAttempt = 0;
        if (isFar(from.toBlockPos(), to)) {
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
        segmentGoal = null;
        segmentAttempt = 0;
        pathTarget = null;
        driftReplanCooldown = 0;
        ticksWithoutProgress = 0;
        assistNeeded = false;
        assistCycles = 0;
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
     * Vybere další mezicíl (s aktuálním laterálním posunem) a přednačte
     * koridor k němu.
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
                // Cíl je pěšky nedosažitelný – šance na odblokování terénu.
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
        // ne až po fyzickém zaseknutí o 2,5 s později.
        if (--validateCooldown <= 0) {
            validateCooldown = VALIDATE_TICKS;
            int limit = Math.min(waypointIndex + VALIDATE_WINDOW, path.waypoints().size() - 1);
            for (int i = waypointIndex; i <= limit; i++) {
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

        // Waypoint dosažen? (ve vodě je svislá tolerance volnější – plave se šikmo)
        if (delta.horizontalLength() < WAYPOINT_TOLERANCE
                && Math.abs(delta.y()) < (inWater ? 1.6 : 1.2)) {
            waypointIndex++;
            if (waypointIndex >= path.waypoints().size()) {
                BlockPos feet = position.toBlockPos();
                if (segmentGoal != null && feet.distanceSquared(segmentGoal) <= SEGMENT_REACHED_SQ) {
                    // Mezicíl dosažen → naplánovat další segment trasy.
                    segmentAttempt = 0;
                    advanceSegment(feet);
                    requestPath(position);
                } else if (!path.complete() && destination != null
                        && currentObjective() != null
                        && feet.distanceSquared(currentObjective()) > 4) {
                    requestPath(position); // částečná cesta – doplánovat
                } else if (segmentGoal != null && destination != null) {
                    // Kompletní cesta skončila kus od mezicíle (normalizace
                    // cíle nad deskou apod.) → pokračovat dalším segmentem.
                    segmentAttempt = 0;
                    advanceSegment(feet);
                    requestPath(position);
                } else if (destination != null && feet.distanceSquared(destination) > 4) {
                    // Cíl mezitím poodešel (drift pohyblivého cíle) – cesta
                    // dojetá, doplánovat zbytek k aktuální pozici cíle.
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
        if (world.traitsAt(waypoint).door() && doorClickCooldown == 0) {
            actions.useItemOn(waypoint, Direction.NORTH);
            doorClickCooldown = 8;
        }

        boolean jump;
        if (inWater) {
            // Plavání: skok = stoupání. Drž ho, když je waypoint na úrovni či výš,
            // nebo když má bot hlavu pod vodou – jinak by se potopil a utopil.
            boolean submerged = world.traitsAt(position.toBlockPos().up()).liquid();
            jump = delta.y() > -0.4 || submerged;
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

        // Skok přes mezeru: waypoint dál než sousední blok ve stejné výšce
        // (nebo o blok níž – dopad s klesáním) znamená naplánovaný přeskok;
        // pathfinder jiné dlouhé segmenty negeneruje. Rozběh sprintem a odraz
        // přesně na hraně – u širší mezery je sprint nutný, aby doletěl.
        boolean gapSegment = onGround && !inWater
                && delta.y() > -1.6 && delta.y() < 0.6
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
            // Rohy, kolem kterých bot zkratkou prošel, odbavit.
            while (waypointIndex < smooth
                    && path.waypoints().get(waypointIndex).center().sub(position)
                            .horizontalLength() < 1.3) {
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
        } else if (destination != null && assistCycles < MAX_ASSIST_CYCLES) {
            // Replany nepomohly – překážka chce zásah (prokopat/přemostit).
            assistNeeded = true;
            path = null;
        } else {
            stop(); // vzdáváme to – cíl si vybere něco jiného
        }
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
        pendingPath = service.request(world, from.toBlockPos(), target, 0, dangers);
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
     */
    public record DebugSnapshot(BlockPos destination, BlockPos segmentGoal,
                                boolean computing, boolean assistNeeded, boolean pathComplete,
                                int waypointIndex, int waypointCount,
                                java.util.List<BlockPos> upcoming) {
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
        return new DebugSnapshot(destination, segmentGoal, pendingPath != null,
                assistNeeded, complete, idx, count, upcoming);
    }
}
