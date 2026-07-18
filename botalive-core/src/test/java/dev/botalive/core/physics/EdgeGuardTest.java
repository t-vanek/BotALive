package dev.botalive.core.physics;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy ochrany hran pro přímý pohyb (útěk, strafing) – klíčové pro End.
 */
class EdgeGuardTest {

    /** Podlaha na y=63, bot stojí na (0.5, 64, 0.5). */
    private static final Vec3 FEET = new Vec3(0.5, 64, 0.5);

    /** Vyhloubí void sloupec (žádná podlaha do hloubky skenu). */
    private static void voidColumn(FakeWorldView world, int x, int z) {
        for (int y = 63; y >= 63 - EdgeGuard.SCAN_DEPTH - 1; y--) {
            world.set(x, y, z, FakeWorldView.AIRLIKE);
        }
    }

    @Test
    void bezpecnaPodlahaNechavaVstupBezeZmeny() {
        FakeWorldView world = new FakeWorldView(63);
        MoveInput input = MoveInput.of(new Vec3(1, 0, 0), true, false);
        assertSame(input, EdgeGuard.apply(world, FEET, input));
    }

    @Test
    void krokDoVoiduSeOtociPodelHrany() {
        FakeWorldView world = new FakeWorldView(63);
        voidColumn(world, 1, 0); // přímo před botem zeje void
        MoveInput input = MoveInput.of(new Vec3(1, 0, 0), true, false);
        MoveInput guarded = EdgeGuard.apply(world, FEET, input);
        assertNotEquals(input.direction(), guarded.direction(), "směr se musí odklonit");
        assertTrue(guarded.direction().horizontalLength() > 0.5, "bot dál jde, jen jinudy");
        assertFalse(guarded.sprint(), "podél hrany se nesprintuje");
    }

    @Test
    void osamelyPilirZastaviPohyb() {
        FakeWorldView world = new FakeWorldView(63);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    voidColumn(world, dx, dz);
                }
            }
        }
        MoveInput guarded = EdgeGuard.apply(world, FEET,
                MoveInput.of(new Vec3(1, 0, 0), true, false));
        assertEquals(MoveInput.IDLE, guarded, "kolem dokola hrana → stát");
    }

    @Test
    void vodaPodHranouJisti() {
        FakeWorldView world = new FakeWorldView(63);
        voidColumn(world, 1, 0);
        world.set(1, 61, 0, FakeWorldView.WATER); // pád do vody je ok
        MoveInput input = MoveInput.of(new Vec3(1, 0, 0), false, false);
        assertSame(input, EdgeGuard.apply(world, FEET, input));
    }

    @Test
    void lavaPodHranouNejisti() {
        FakeWorldView world = new FakeWorldView(63);
        voidColumn(world, 1, 0);
        world.set(1, 61, 0, FakeWorldView.HAZARD); // láva – to není záchrana
        MoveInput input = MoveInput.of(new Vec3(1, 0, 0), false, false);
        MoveInput guarded = EdgeGuard.apply(world, FEET, input);
        assertNotEquals(input.direction(), guarded.direction());
    }

    @Test
    void melkaLavaNadDnemNejisti() {
        // Láva v úrovni nohou nad pevným dnem: dno je „podlaha", ale krok
        // do lávy je rozsudek – sloupec s lávou nesmí projít jako bezpečný.
        FakeWorldView world = new FakeWorldView(63);
        world.set(1, 64, 0, FakeWorldView.HAZARD); // láva v cílové buňce
        MoveInput input = MoveInput.of(new Vec3(1, 0, 0), false, false);
        MoveInput guarded = EdgeGuard.apply(world, FEET, input);
        assertNotEquals(input.direction(), guarded.direction(),
                "do lávy se nekráčí, i když je pod ní dno");
    }

    @Test
    void lavaVUhybovemSmeruSeVynechava() {
        // Přímý směr vede do voidu, první úhyb (45°) do lávy nad dnem –
        // guard musí lávový úhyb přeskočit a najít další bezpečný.
        FakeWorldView world = new FakeWorldView(63);
        voidColumn(world, 1, 0);
        world.set(1, 63, 1, FakeWorldView.HAZARD); // 45° kandidát: mělká láva
        MoveInput input = MoveInput.of(new Vec3(1, 0, 0), true, false);
        MoveInput guarded = EdgeGuard.apply(world, FEET, input);
        assertTrue(guarded.direction().horizontalLength() > 0.5, "jde se dál, jinudy");
        // Ani do voidu, ani do lávy: výsledný směr musí být bezpečný.
        assertTrue(EdgeGuard.safeAhead(world, FEET,
                guarded.direction().normalized(), EdgeGuard.SCAN_DEPTH));
    }

    @Test
    void stojiciVstupSeNekontroluje() {
        FakeWorldView world = new FakeWorldView(63);
        assertSame(MoveInput.IDLE, EdgeGuard.apply(world, FEET, MoveInput.IDLE));
    }
}
