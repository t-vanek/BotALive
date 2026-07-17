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

import java.util.concurrent.CompletableFuture;

/**
 * Vykonavatel cest – převádí naplánovanou {@link Path} na pohybové vstupy.
 *
 * <p>Vlastnosti:</p>
 * <ul>
 *   <li>waypoint se považuje za dosažený s tolerancí (bot nechodí „po pravítku"),</li>
 *   <li>sprint se zapíná na delších rovných úsecích podle povahy bota,</li>
 *   <li>skok se vyvolá, když je další waypoint výš,</li>
 *   <li>mezery 1–2 bloků (naplánované pathfinderem) se přeskakují rozběhem
 *       a odrazem na hraně; širší mezera vynucuje sprint,</li>
 *   <li>zavřené dveře v cestě bot otevře interakcí,</li>
 *   <li>detekce zaseknutí: bez postupu několik desítek ticků → požádá o novou cestu,</li>
 *   <li>replanning je asynchronní – bot mezitím dojíždí starou cestu.</li>
 * </ul>
 */
public final class Navigator {

    /** Tolerance dosažení waypointu (bloky, horizontálně). */
    private static final double WAYPOINT_TOLERANCE = 0.45;

    /** Po kolika ticích bez postupu se považujeme za zaseknuté. */
    private static final int STUCK_TICKS = 50;

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
    private CompletableFuture<Path> pendingPath;

    private Vec3 lastProgressPos = Vec3.ZERO;
    private int ticksWithoutProgress;
    private int repathAttempts;
    /** Zbývající ticky „odskakovacího" pokusu při zaseknutí (nízká překážka). */
    private int unstuckJumpTicks;

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
        destination = to;
        repathAttempts = 0;
        assistNeeded = false;
        assistCycles = 0;
        requestPath(from);
    }

    /** Zruší navigaci. */
    public void stop() {
        path = null;
        waypointIndex = 0;
        destination = null;
        pendingPath = null;
        ticksWithoutProgress = 0;
        assistNeeded = false;
        assistCycles = 0;
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
     * Jeden tick navigace.
     *
     * @param position aktuální pozice bota (nohy)
     * @param onGround bot na zemi
     * @param inWater  bot je ve vodě (plavecký režim: svislý pohyb skokem)
     * @return pohybový vstup pro fyziku (IDLE pokud není kam jít)
     */
    public MoveInput tick(Vec3 position, boolean onGround, boolean inWater) {
        // Během čekání na zásah do terénu bot stojí.
        if (assistNeeded) {
            return MoveInput.IDLE;
        }
        // Vyzvednout dopočítanou cestu.
        if (pendingPath != null && pendingPath.isDone()) {
            Path computed = pendingPath.join();
            pendingPath = null;
            org.slf4j.LoggerFactory.getLogger(Navigator.class).debug(
                    "[nav] cesta: {} waypointů, complete={}, cíl={}",
                    computed.waypoints().size(), computed.complete(), destination);
            if (!computed.isEmpty()) {
                path = computed;
                waypointIndex = 0;
            } else if (destination != null) {
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

        BlockPos waypoint = path.waypoints().get(waypointIndex);
        Vec3 target = waypoint.center();
        Vec3 delta = target.sub(position);

        // Waypoint dosažen? (ve vodě je svislá tolerance volnější – plave se šikmo)
        if (delta.horizontalLength() < WAYPOINT_TOLERANCE
                && Math.abs(delta.y()) < (inWater ? 1.6 : 1.2)) {
            waypointIndex++;
            if (waypointIndex >= path.waypoints().size()) {
                if (!path.complete() && destination != null
                        && position.toBlockPos().distanceSquared(destination) > 4) {
                    requestPath(position); // částečná cesta – doplánovat
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

        // Zavřené dveře před nosem → otevřít.
        if (world.traitsAt(waypoint).door()) {
            actions.useItemOn(waypoint, Direction.NORTH);
        }

        boolean jump;
        if (inWater) {
            // Plavání: skok = stoupání. Drž ho, když je waypoint na úrovni či výš,
            // nebo když má bot hlavu pod vodou – jinak by se potopil a utopil.
            boolean submerged = world.traitsAt(position.toBlockPos().up()).liquid();
            jump = delta.y() > -0.4 || submerged;
        } else {
            jump = delta.y() > 0.5 && onGround;
            if (unstuckJumpTicks > 0) {
                unstuckJumpTicks--;
                jump = jump || onGround; // odskočit nízkou překážku (deska, koberec…)
            }
        }
        Vec3 direction = delta.horizontal().normalized();

        // Skok přes mezeru: waypoint dál než sousední blok ve stejné výšce
        // znamená naplánovaný přeskok (pathfinder jiné dlouhé segmenty negeneruje).
        // Rozběh sprintem a odraz přesně na hraně – u širší mezery je sprint
        // nutný, aby doletěl.
        boolean gapSegment = onGround && !inWater && Math.abs(delta.y()) < 0.6
                && delta.horizontalLength() > 1.4;
        if (gapSegment) {
            boolean wideGap = delta.horizontalLength() > 2.2;
            if (takeoffEdge(position, direction)) {
                return new MoveInput(direction, wideGap, true, false);
            }
            return new MoveInput(direction, wideGap, false, false);
        }

        // Hrana srázu: před botem zeje díra hlubší než bezpečný seskok a cesta
        // dolů nevede → přibrzdit, ať setrvačnost nepřenese bota přes okraj.
        if (onGround && !inWater && !jump && delta.y() > -0.5 && cliffAhead(position, direction)) {
            return new MoveInput(direction.mul(0.25), false, false, false);
        }

        boolean sprint = !inWater && shouldSprint(delta);
        return MoveInput.of(direction, sprint, jump);
    }

    /** Odrazová hrana: kousek před botem už chybí pevná podlaha pod nohama. */
    private boolean takeoffEdge(Vec3 position, Vec3 direction) {
        BlockPos ahead = position.add(direction.mul(0.7)).toBlockPos();
        return !world.traitsAt(ahead.down()).solid();
    }

    /** Sloupec kousek před botem nemá do 4 bloků pod nohama nic pevného ani vodu. */
    private boolean cliffAhead(Vec3 position, Vec3 direction) {
        BlockPos ahead = position.add(direction.mul(0.9)).toBlockPos();
        for (int depth = 1; depth <= 4; depth++) {
            var traits = world.traitsAt(ahead.offset(0, -depth, 0));
            if (traits.solid() || traits.liquid()) {
                return false;
            }
        }
        return true;
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
        if (world == null || destination == null) {
            return;
        }
        java.util.List<BlockPos> dangers = dangerSupplier != null
                ? dangerSupplier.get() : java.util.List.of();
        pendingPath = service.findPath(world, from.toBlockPos(), destination, 0, dangers);
    }
}
