package dev.botalive.core.pathfinding;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy konzervativní validace waypointů proti změnám světa.
 *
 * <p>Klíčový invariant: validátor smí zneplatnit jen stavy, které by odmítl
 * i A* – jinak by replán vyprodukoval tutéž cestu a bot by se zacyklil.</p>
 */
class PathValidatorTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    private static final BlockPos WP = new BlockPos(5, FEET, 5);

    @Test
    void volnyWaypointNeblokuje() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        assertFalse(PathValidator.blocked(world, WP));
    }

    @Test
    void zazdenyWaypointBlokuje() {
        FakeWorldView world = new FakeWorldView(FLOOR)
                .set(5, FEET, 5, FakeWorldView.SOLID);
        assertTrue(PathValidator.blocked(world, WP), "postavená zeď má cestu zneplatnit");
    }

    @Test
    void lavaVeWaypointuBlokuje() {
        FakeWorldView world = new FakeWorldView(FLOOR)
                .set(5, FEET, 5, FakeWorldView.HAZARD);
        assertTrue(PathValidator.blocked(world, WP), "nateklá láva má cestu zneplatnit");
    }

    @Test
    void strzenaPodlahaBlokuje() {
        FakeWorldView world = new FakeWorldView(FLOOR)
                .set(5, FLOOR, 5, FakeWorldView.AIRLIKE);
        assertTrue(PathValidator.blocked(world, WP), "vykopnutá podlaha má cestu zneplatnit");
    }

    @Test
    void neznamoNechavaCestuByt() {
        // Chunk vypršel z cache – to není důvod k replánu (storm nad dlouhou cestou).
        FakeWorldView world = new FakeWorldView(FLOOR)
                .set(5, FEET, 5, BlockTraits.UNKNOWN);
        assertFalse(PathValidator.blocked(world, WP));
    }

    @Test
    void vodaNebrani() {
        FakeWorldView world = new FakeWorldView(FLOOR)
                .set(5, FEET, 5, FakeWorldView.WATER);
        assertFalse(PathValidator.blocked(world, WP), "plavecký waypoint funguje dál");
    }

    @Test
    void zavreneDvereNebrani() {
        FakeWorldView world = new FakeWorldView(FLOOR)
                .set(5, FEET, 5, FakeWorldView.DOOR_CLOSED);
        assertFalse(PathValidator.blocked(world, WP), "dveře si bot otevře");
    }

    @Test
    void prekazkaVHlaveBlokuje() {
        FakeWorldView world = new FakeWorldView(FLOOR)
                .set(5, FEET + 1, 5, FakeWorldView.SOLID);
        assertTrue(PathValidator.blocked(world, WP), "blok v úrovni hlavy má cestu zneplatnit");
    }

    @Test
    void horniPoklopVHlaveNebrani() {
        // Kolize u stropu buňky (horní poklop) tělu nepřekáží a A* přes ni
        // plánuje – validátor ji nesmí hlásit, jinak by se replán zacyklil.
        FakeWorldView world = new FakeWorldView(FLOOR)
                .set(5, FEET + 1, 5, FakeWorldView.SOLID.withBoxes(
                        new double[]{0, 0.8125, 0, 1, 1, 1}));
        assertFalse(PathValidator.blocked(world, WP));
    }
}
