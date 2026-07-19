package dev.botalive.core.pathfinding;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.physics.BotPhysics;
import dev.botalive.core.physics.FallReflex;
import dev.botalive.core.physics.LiquidReflex;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Perturbační kontrakt (v4.2): exekuce cest pod rušením. Dosavadní
 * simulace běží v čistých světech – tady do bota průběžně strká
 * „server" ({@link BotPhysics#setVelocity} – stejný kanál jako
 * knockback od mobů a šípů v produkci) a kontrakt zní: bot se
 * vzpamatuje a dorazí, případný pád zachytí reflexy a replán, poškození
 * z pádů nula. Rozvrhy strkání jsou deterministické (seedovaná náhoda).
 */
class PerturbationSimulationTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    /** Vanilla melee knockback: ~0.45 vodorovně + 0.36 svisle. */
    private static final double SHOVE_HORIZONTAL = 0.45;
    private static final double SHOVE_VERTICAL = 0.36;

    private final NavigationService service = new NavigationService(1);

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    /** Rozvrh strkání – dostane tick a fyziku, případně strčí. */
    @FunctionalInterface
    private interface Perturber {
        void maybeShove(int tick, BotPhysics physics);
    }

    @Test
    void dojdeIPresOpakovaneStrkani() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BotRandom shoveRng = new BotRandom(13);
        int[] shoves = {0};
        simulate(world, at(0), new BlockPos(15, FEET, 0), 1600, (tick, physics) -> {
            if (tick > 0 && tick % 25 == 0) {
                shoves[0]++;
                double angle = shoveRng.rangeInt(0, 360) * Math.PI / 180.0;
                physics.setVelocity(new Vec3(Math.cos(angle) * SHOVE_HORIZONTAL,
                        SHOVE_VERTICAL, Math.sin(angle) * SHOVE_HORIZONTAL));
            }
        });
        assertTrue(shoves[0] >= 2, "scénář má bota opravdu strkat, strčení: " + shoves[0]);
    }

    @Test
    void vzpamatujeSeZeStrceniDoPrikopu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Mělký příkop (hloubka 1) podél trasy – strkání k němu bota občas
        // shodí dolů; kontrakt: vyskáče ven a dojde bez eskalace.
        for (int x = 3; x <= 12; x++) {
            for (int z = 1; z <= 2; z++) {
                world.set(x, FLOOR, z, FakeWorldView.AIRLIKE);
            }
        }
        int[] shoves = {0};
        simulate(world, at(0), new BlockPos(15, FEET, 0), 2400, (tick, physics) -> {
            if (tick > 0 && tick % 30 == 0) {
                shoves[0]++;
                physics.setVelocity(new Vec3(0, SHOVE_VERTICAL, SHOVE_HORIZONTAL));
            }
        });
        assertTrue(shoves[0] >= 2, "scénář má bota opravdu strkat, strčení: " + shoves[0]);
    }

    @Test
    void prezijeStrceniVLetuNadMezerou() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Suchá jáma hloubky 4 s 2 bloky vody na dně (x 3..4): plánovač ji
        // sprint-skokem přeskakuje. Strčení přesně uprostřed letu smete bota
        // z dráhy – dopad jistí voda na dně, ven vyplave, vyhoupne se
        // a doskáče. Kontrakt: dorazí bez poškození.
        for (int x = 3; x <= 4; x++) {
            for (int z = -8; z <= 8; z++) {
                world.set(x, FLOOR, z, FakeWorldView.AIRLIKE);
                world.set(x, FLOOR - 1, z, FakeWorldView.AIRLIKE);
                world.set(x, FLOOR - 2, z, FakeWorldView.WATER);
                world.set(x, FLOOR - 3, z, FakeWorldView.WATER);
            }
        }
        boolean[] shoved = {false};
        simulate(world, at(0), new BlockPos(8, FEET, 0), 2400, (tick, physics) -> {
            Vec3 pos = physics.position();
            if (!shoved[0] && !physics.onGround() && pos.y() > FEET + 0.2
                    && pos.x() > 2.8 && pos.x() < 4.5) {
                shoved[0] = true; // jediné strčení přesně uprostřed letu
                physics.setVelocity(new Vec3(0, 0.1, SHOVE_HORIZONTAL * 1.4));
            }
        });
        assertTrue(shoved[0], "strčení mělo bota zasáhnout uprostřed rozskoku");
    }

    @Test
    void vysplhaZebrikIPresStrceni() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Uzavřený koridor s příčnou zdí a žebříkem (jako vysplhaZebrikPresZed);
        // strkání uprostřed šplhu bota srazí, kontrakt: znovu vyleze a dojde.
        for (int x = -1; x <= 7; x++) {
            world.wall(x, FEET, FEET + 3, -1);
            world.wall(x, FEET, FEET + 3, 1);
        }
        for (int z = -1; z <= 1; z++) {
            world.wall(-1, FEET, FEET + 3, z);
            world.wall(7, FEET, FEET + 3, z);
        }
        world.wall(3, FEET, FEET + 2, 0);
        for (int y = FEET; y <= FEET + 2; y++) {
            world.set(2, y, 0, FakeWorldView.CLIMBABLE);
        }
        BotRandom shoveRng = new BotRandom(29);
        int[] shoves = {0};
        simulate(world, at(0), new BlockPos(6, FEET, 0), 2400, (tick, physics) -> {
            Vec3 pos = physics.position();
            boolean climbing = pos.y() > FEET + 0.8 && pos.y() < FEET + 2.5
                    && pos.x() > 1.5 && pos.x() < 3.0;
            if (climbing && shoveRng.chance(0.08)) {
                shoves[0]++;
                physics.setVelocity(new Vec3(-SHOVE_HORIZONTAL, 0, 0)); // sraz ze žebříku
            }
        });
        assertTrue(shoves[0] >= 1, "scénář má bota srážet ze žebříku, strčení: " + shoves[0]);
    }

    // ------------------------------------------------------------------ smyčka

    private void simulate(FakeWorldView world, Vec3 start, BlockPos goal, int maxTicks,
                          Perturber perturber) {
        Navigator navigator = new Navigator(service, null, new BotRandom(7), personality());
        navigator.world(world);
        BotPhysics physics = new BotPhysics(world, start);
        navigator.navigateTo(start, goal);

        int fallDamage = 0;
        for (int tick = 0; tick < maxTicks; tick++) {
            if (navigator.navigating() && !navigator.hasPath()) {
                sleep(1);
            }
            perturber.maybeShove(tick, physics);
            MoveInput input = navigator.tick(physics.position(), physics.onGround(),
                    physics.inWater());
            input = LiquidReflex.apply(input, navigator.hasPath(), physics.position(),
                    physics.submergedTicks(), world);
            input = FallReflex.apply(input, navigator.hasPath(), physics.onGround(),
                    physics.fallDistance(), physics.position(), world);
            physics.step(input);
            if (physics.landedThisTick()) {
                fallDamage += physics.lastFallDamage();
            }
            BlockPos feet = physics.position().toBlockPos();
            assertTrue(!world.traitsAt(feet).hazard(),
                    "bot vkročil do hazardu na " + feet + " (tick " + tick + ")");
            assertTrue(!navigator.needsAssist(),
                    "strkání nemá vést k terraformingu (tick " + tick
                            + ", pozice " + physics.position() + ")");
            if (arrived(physics.position(), goal)) {
                assertEquals(0, fallDamage, "zotavení nemělo bolet (poškození z pádů)");
                return;
            }
        }
        throw new AssertionError("bot se ze strkání nevzpamatoval; skončil na "
                + physics.position());
    }

    /** Dorazil: nohy v okruhu 0,9 bloku od středu cílové buňky (±1,1 na výšku). */
    private static boolean arrived(Vec3 position, BlockPos goal) {
        double dx = position.x() - (goal.x() + 0.5);
        double dz = position.z() - (goal.z() + 0.5);
        return dx * dx + dz * dz < 0.9 * 0.9 && Math.abs(position.y() - goal.y()) < 1.1;
    }

    private static Vec3 at(int x) {
        return new Vec3(x + 0.5, FEET, 0.5);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Personality personality() {
        return new Personality() {
            @Override
            public double trait(Trait trait) {
                return 0.5;
            }

            @Override
            public Map<Trait, Double> traits() {
                return Map.of();
            }

            @Override
            public long seed() {
                return 1;
            }

            @Override
            public String archetype() {
                return "sim";
            }
        };
    }
}
