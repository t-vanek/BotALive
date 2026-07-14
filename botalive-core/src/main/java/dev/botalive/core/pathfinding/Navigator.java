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

    private WorldView world;
    private Path path;
    private int waypointIndex;
    private BlockPos destination;
    private CompletableFuture<Path> pendingPath;

    private Vec3 lastProgressPos = Vec3.ZERO;
    private int ticksWithoutProgress;
    private int repathAttempts;

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
        if (to.equals(destination) && (hasPath() || pendingPath != null)) {
            return; // už tam jdeme
        }
        destination = to;
        repathAttempts = 0;
        requestPath(from);
    }

    /** Zruší navigaci. */
    public void stop() {
        path = null;
        waypointIndex = 0;
        destination = null;
        pendingPath = null;
        ticksWithoutProgress = 0;
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
     * @return pohybový vstup pro fyziku (IDLE pokud není kam jít)
     */
    public MoveInput tick(Vec3 position, boolean onGround) {
        // Vyzvednout dopočítanou cestu.
        if (pendingPath != null && pendingPath.isDone()) {
            Path computed = pendingPath.join();
            pendingPath = null;
            if (!computed.isEmpty()) {
                path = computed;
                waypointIndex = 0;
            } else if (destination != null) {
                stop(); // cíl je nedosažitelný
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

        // Waypoint dosažen?
        if (delta.horizontalLength() < WAYPOINT_TOLERANCE && Math.abs(delta.y()) < 1.2) {
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

        boolean jump = delta.y() > 0.5 && onGround;
        boolean sprint = shouldSprint(delta);
        return MoveInput.of(delta.horizontal(), sprint, jump);
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
        if (++ticksWithoutProgress < STUCK_TICKS) {
            return;
        }
        ticksWithoutProgress = 0;
        if (destination != null && repathAttempts < 3) {
            repathAttempts++;
            path = null;
            requestPath(position);
        } else {
            stop(); // vzdáváme to – cíl si vybere něco jiného
        }
    }

    private void requestPath(Vec3 from) {
        if (world == null || destination == null) {
            return;
        }
        pendingPath = service.findPath(world, from.toBlockPos(), destination, 0);
    }
}
