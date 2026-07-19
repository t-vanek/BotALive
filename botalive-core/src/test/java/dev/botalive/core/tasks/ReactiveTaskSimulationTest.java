package dev.botalive.core.tasks;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.physics.BotPhysics;
import dev.botalive.core.testutil.FakeBotContext;
import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fyzická simulace reaktivních tasků: pilíř, most a žebřík se nejen
 * naplánují, ale proti reálné {@link BotPhysics} skutečně vykonají –
 * task řídí pohyb ({@code move()}) i akce (pokládání přes
 * {@link FakeBotContext}), fyzika rozhoduje, kde bot opravdu je.
 * Selhání znamená mezeru mezi mechanikou tasku a kolizním systémem
 * (načasování skoku pilíře, šoupání po hraně mostu, plynulost šplhu).
 */
class ReactiveTaskSimulationTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    @Test
    void pilirVystoupaFyzicky() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        FakeBotContext ctx = new FakeBotContext(world, personality())
                .give(Material.COBBLESTONE, 12);
        BotPhysics physics = new BotPhysics(world, at(0));
        PillarUpTask task = new PillarUpTask(FEET + 4);

        run(task, ctx, physics, world, 1200);

        assertTrue(task.succeeded(), "pilíř měl vystoupat: " + physics.position());
        assertTrue(physics.position().y() >= FEET + 4 - 0.01,
                "bot má stát o 4 výš: " + physics.position());
        assertTrue(ctx.placed() >= 4, "pilíř o 4 chce aspoň 4 bloky, je " + ctx.placed());
    }

    @Test
    void mostPrekleneMezeruFyzicky() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Propast šířky 4 (x 3..6) přes celou šíři, hloubka 12 – pád by bolel
        // a stráž poškození by ho chytila.
        for (int x = 3; x <= 6; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR - 11; y <= FLOOR; y++) {
                    world.set(x, y, z, FakeWorldView.AIRLIKE);
                }
            }
        }
        FakeBotContext ctx = new FakeBotContext(world, personality())
                .give(Material.COBBLESTONE, 12);
        BotPhysics physics = new BotPhysics(world, at(2));
        BridgeTask task = new BridgeTask(1, 0);

        run(task, ctx, physics, world, 2400);

        assertTrue(task.succeeded(), "most měl dojít na protější břeh: " + physics.position());
        assertTrue(physics.position().x() > 6.5,
                "bot má stát za propastí: " + physics.position());
        assertTrue(ctx.placed() >= 4, "mezera 4 chce aspoň 4 bloky mostu, je " + ctx.placed());
    }

    @Test
    void zebrikPrelezeZedFyzicky() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Zeď výšky 3 (x=3, z −1..1) – na výskok moc, žebřík akorát.
        for (int z = -1; z <= 1; z++) {
            world.wall(3, FEET, FEET + 2, z);
        }
        FakeBotContext ctx = new FakeBotContext(world, personality())
                .give(Material.LADDER, 8);
        BotPhysics physics = new BotPhysics(world, at(2));
        LadderTask task = new LadderTask(1, 0);

        run(task, ctx, physics, world, 1600);

        assertTrue(task.succeeded(), "žebřík měl zeď přelézt: " + physics.position());
        BlockPos feet = physics.position().toBlockPos();
        assertEquals(FEET + 3, feet.y(), "bot má dosednout na vršek zdi: " + feet);
        assertEquals(3, ctx.placed(), "zeď výšky 3 = 3 příčky");
    }

    // ------------------------------------------------------------------ smyčka

    /**
     * Odsimuluje task do konce: task řídí pohyb i akce, fyzika polohu.
     * Selhání = poškození z pádu, hazard pod nohama, nebo nedoběhnutí.
     */
    private static void run(BotTask task, FakeBotContext ctx, BotPhysics physics,
                            FakeWorldView world, int maxTicks) {
        for (int tick = 0; tick < maxTicks; tick++) {
            ctx.update(physics.position(), physics.onGround());
            boolean done = task.tick(ctx);
            physics.step(task.move());
            if (physics.landedThisTick() && physics.lastFallDamage() > 0) {
                throw new AssertionError("task neměl bolet (tick " + tick
                        + ", poškození " + physics.lastFallDamage() + ")");
            }
            BlockPos feet = physics.position().toBlockPos();
            assertTrue(!world.traitsAt(feet).hazard(),
                    "bot vkročil do hazardu na " + feet + " (tick " + tick + ")");
            if (done) {
                return;
            }
        }
        throw new AssertionError("task neskončil do " + maxTicks
                + " ticků; pozice " + physics.position());
    }

    private static Vec3 at(int x) {
        return new Vec3(x + 0.5, FEET, 0.5);
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
