package dev.botalive.core.physics;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy pádového reflexu – přikrčení u nebezpečné hrany a vzdušný clutch.
 */
class FallReflexTest {

    private static final int FLOOR = 63;
    private static final double FEET_Y = FLOOR + 1;

    /** Platforma bloků na úrovni FLOOR pro x,z v [−2..2]; kolem hluboké prázdno. */
    private static FakeWorldView platform() {
        FakeWorldView world = new FakeWorldView(0);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
        }
        return world;
    }

    private static MoveInput east() {
        return new MoveInput(new Vec3(1, 0, 0), true, false, false); // sprint, ať je co potlačit
    }

    @Test
    void nebezpecnaHranaPrikrci() {
        MoveInput out = FallReflex.apply(east(), false, true, 0.0,
                new Vec3(2.5, FEET_Y, 0.5), platform());

        assertTrue(out.sneak(), "u srázu se má bot přikrčit");
        assertFalse(out.sprint(), "u srázu bot nesprintuje");
        assertFalse(out.jump(), "u srázu bot neskáče");
        assertTrue(out.direction().x() > 0, "směr chůze zůstává zachován");
    }

    @Test
    void rovnaZemBezeZmeny() {
        FakeWorldView world = new FakeWorldView(FLOOR); // plná podlaha
        MoveInput in = east();
        MoveInput out = FallReflex.apply(in, false, true, 0.0, new Vec3(0.5, FEET_Y, 0.5), world);

        assertSame(in, out, "na rovině reflex nic nemění");
    }

    @Test
    void bezpecnySeskokNeprikrci() {
        // Plná podlaha, ale o krok dál chybí horní blok → jen 1 blok seskok (bezpečný).
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(3, FLOOR, 0, BlockTraits.AIR); // (3,63) díra; pod ní (3,62) je pevná

        MoveInput out = FallReflex.apply(east(), false, true, 0.0,
                new Vec3(2.5, FEET_Y, 0.5), world);

        assertFalse(out.sneak(), "bezpečný seskok (1 blok) nemá spouštět reflex");
    }

    @Test
    void naplanovanaCestaNepresahne() {
        // Stejná nebezpečná hrana, ale řízený pohyb → reflex mlčí.
        MoveInput in = east();
        MoveInput out = FallReflex.apply(in, true, true, 0.0,
                new Vec3(2.5, FEET_Y, 0.5), platform());

        assertSame(in, out, "plánované seskoky reflex neruší");
    }

    @Test
    void kratkyPadVeVzduchuMlci() {
        MoveInput in = east();
        MoveInput out = FallReflex.apply(in, false, false, 1.0,
                new Vec3(2.5, FEET_Y + 2, 0.5), platform());

        assertSame(in, out, "krátký pád (≤ 3 bloky) reflex neřeší");
    }

    @Test
    void clutchKormidlujeKVode() {
        // Tvrdá podlaha všude, jen sloupec x=3 je vodní jezírko.
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(3, FLOOR, 0, FakeWorldView.WATER);

        // Bot padá nad x=0 z výšky – pod ním tvrdo, voda je 3 bloky na východ.
        MoveInput out = FallReflex.apply(MoveInput.IDLE, false, false, 5.0,
                new Vec3(0.5, FEET_Y + 6, 0.5), world);

        assertTrue(out.direction().x() > 0.9,
                "clutch má kormidlovat k vodě na východě, dir=" + out.direction());
        assertFalse(out.sneak(), "při clutchi se bot neplíží");
    }

    @Test
    void clutchNadVodouNerusi() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(0, FLOOR, 0, FakeWorldView.WATER);

        // Pod botem je voda – reflex nemá do pádu sahat.
        MoveInput in = east();
        MoveInput out = FallReflex.apply(in, false, false, 5.0,
                new Vec3(0.5, FEET_Y + 6, 0.5), world);

        assertSame(in, out, "pád do vody clutch nechává být");
    }

    @Test
    void clutchNaMekkyBlok() {
        // Tvrdá podlaha, jen (−2, z=0) je seno – clutch míří na západ.
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(-2, FLOOR + 1, 0, FakeWorldView.SOFT);

        MoveInput out = FallReflex.apply(MoveInput.IDLE, false, false, 6.0,
                new Vec3(0.5, FEET_Y + 8, 0.5), world);

        assertTrue(out.direction().x() < -0.9,
                "clutch má kormidlovat na seno na západě, dir=" + out.direction());
    }

    @Test
    void clutchBezZachranyNerusi() {
        // Všude tvrdo, žádná voda ani měkký blok v dosahu.
        FakeWorldView world = new FakeWorldView(FLOOR);
        MoveInput in = east();
        MoveInput out = FallReflex.apply(in, false, false, 8.0,
                new Vec3(0.5, FEET_Y + 10, 0.5), world);

        assertSame(in, out, "bez záchrany v dosahu se vstup nemění");
    }
}
