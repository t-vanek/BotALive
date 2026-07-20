package dev.botalive.core.combat;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.pathfinding.NavigationService;
import dev.botalive.core.pathfinding.Navigator;
import dev.botalive.core.physics.BotPhysics;
import dev.botalive.core.physics.FallReflex;
import dev.botalive.core.physics.LiquidReflex;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.testutil.FakeBotContext;
import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fyzická simulace spolupráce boje s navigací (v4.0): bojový pohyb se
 * nejen spočítá, ale proti reálné {@link BotPhysics} skutečně vykoná.
 * Kontrakt: bot v boji obejde překážku (konec kitingu) a při nízkém
 * zdraví ustoupí po pochozím terénu, nikdy do hazardu.
 */
class CombatNavigationSimulationTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    private final NavigationService service = new NavigationService(1);

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void obejdeZedKeKitujicimuCili() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // L-zeď výšky 2 mezi botem a cílem; průchod existuje jen jižně (z ≥ 3).
        for (int z = -6; z <= 2; z++) {
            world.wall(4, FEET, FEET + 1, z);
        }
        TrackedEntity target = new TrackedEntity(99, UUID.randomUUID(),
                EntityType.PLAYER, new Vec3(8.5, FEET, 0.5));

        Harness h = harness(world);
        h.combat.engage(target);

        boolean detoured = false;
        for (int tick = 0; tick < 1600; tick++) {
            if (h.navigator.navigating() && !h.navigator.hasPath()) {
                sleep(1);
            }
            step(h, world, target, 20.0f);
            detoured |= h.physics.position().z() > 2.5;
            if (h.physics.position().distance(target.position()) <= 3.0) {
                assertTrue(detoured, "k cíli za zdí se dá dostat jen obchůzkou jihem");
                return;
            }
        }
        throw new AssertionError("bot cíl za zdí neobešel; skončil na "
                + h.physics.position());
    }

    @Test
    void ustoupiKolemLavyPoPochozimTerenu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Lávové pole za zády bota (přímé couvání od cíle by vedlo do něj).
        for (int x = -7; x <= -5; x++) {
            for (int z = -8; z <= 8; z++) {
                world.set(x, FEET, z, FakeWorldView.HAZARD);
                world.set(x, FLOOR, z, FakeWorldView.HAZARD);
            }
        }
        TrackedEntity target = new TrackedEntity(99, UUID.randomUUID(),
                EntityType.PLAYER, new Vec3(2.5, FEET, 0.5));

        Harness h = harness(world);
        h.combat.engage(target);

        for (int tick = 0; tick < 1600; tick++) {
            if (h.navigator.navigating() && !h.navigator.hasPath()) {
                sleep(1);
            }
            step(h, world, target, 4.0f); // nízké zdraví → ústup
            Vec3 pos = h.physics.position();
            double away = pos.horizontal().distance(target.position().horizontal());
            if (away >= 11.0) {
                return; // utekl na bezpečnou vzdálenost, do lávy nevkročil
            }
        }
        throw new AssertionError("bot neustoupil na bezpečnou vzdálenost; skončil na "
                + h.physics.position());
    }

    // ------------------------------------------------------------------ smyčka

    private record Harness(CombatController combat, Navigator navigator, BotPhysics physics) {
    }

    private Harness harness(FakeWorldView world) {
        FakeBotContext ctx = new FakeBotContext(world, personality());
        Navigator navigator = new Navigator(service, null, new BotRandom(7), personality());
        navigator.world(world);
        CombatController combat = new CombatController(ctx.actions(), ctx.humanizer(),
                new BotRandom(7), personality(),
                new BotAliveConfig.Combat(true, 120, 250, true, false, false),
                CombatDifficulty.NORMAL, ctx.inventory());
        combat.navigation(navigator);
        combat.world(world);
        return new Harness(combat, navigator, new BotPhysics(world, new Vec3(0.5, FEET, 0.5)));
    }

    /** Jeden tick: boj → (případně) navigace → reflexy → fyzika + stráže. */
    private static void step(Harness h, FakeWorldView world, TrackedEntity target,
                             float health) {
        MoveInput input = h.combat.tick(h.physics.position(), health,
                h.physics.onGround(), null);
        boolean navDriven = false;
        if (input == null) {
            input = h.navigator.tick(h.physics.position(), h.physics.onGround(),
                    h.physics.inWater());
            navDriven = h.navigator.hasPath();
        }
        input = LiquidReflex.apply(input, navDriven, h.physics.position(),
                h.physics.submergedTicks(), world);
        input = FallReflex.apply(input, navDriven, h.physics.onGround(),
                h.physics.fallDistance(), h.physics.position(), world);
        h.physics.step(input);
        BlockPos feet = h.physics.position().toBlockPos();
        assertTrue(!world.traitsAt(feet).hazard(),
                "bot vkročil do hazardu na " + feet);
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
