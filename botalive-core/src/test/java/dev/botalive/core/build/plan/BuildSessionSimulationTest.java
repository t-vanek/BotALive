package dev.botalive.core.build.plan;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.testutil.FakeBotContext;
import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import dev.botalive.core.util.Vec3;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Konec do konce: {@link BuildSession} na syntetickém světě postaví celý dům,
 * studnu i sýpku (bloky skutečně vzniknou), přeskočí došlý materiál a po
 * doplnění dostaví, a při návratu k rozestavěné stavbě klade jen zbytek –
 * fyzická stavba je autorita. Vzor {@code ReactiveTaskSimulationTest}.
 */
class BuildSessionSimulationTest {

    private static final int FLOOR_Y = 63;
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

    /** Postaví bota přesně na stanoviště plánu (GOTO_STAND je pak no-op). */
    private static FakeBotContext botAt(FakeWorldView world, BlockPos stand) {
        FakeBotContext ctx = new FakeBotContext(world, personality());
        ctx.update(new Vec3(stand.x() + 0.5, stand.y(), stand.z() + 0.5), true);
        return ctx;
    }

    private static BuildSession.State run(FakeBotContext ctx, BuildSession session, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            BuildSession.State state = session.tick(ctx);
            if (state != BuildSession.State.RUNNING) {
                return state;
            }
        }
        return BuildSession.State.RUNNING; // timeout
    }

    private static boolean allSolid(FakeWorldView world, BuildPlan plan) {
        return plan.cells().stream().allMatch(c -> world.traitsAt(c.pos()).solid());
    }

    @Test
    void houseBuildsCompletelyWithFurnishing() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BuildPlan plan = BuildPlan.of(Blueprints.house(), ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);
        FakeBotContext ctx = botAt(world, plan.stand())
                .give(Material.COBBLESTONE, 500)
                .give(Material.OAK_DOOR, 1)
                .give(Material.TORCH, 1)
                .give(Material.RED_BED, 1);

        assertEquals(BuildSession.State.DONE, run(ctx, new BuildSession(schedule), 3000));
        assertTrue(allSolid(world, plan), "všechny bloky domu stojí");
        for (FurnishCell f : plan.furnishing()) {
            assertTrue(world.traitsAt(f.pos()).solid(), "vybavení osazeno: " + f.kind());
        }
    }

    @Test
    void wellBuildsRingAndTorch() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BuildPlan plan = BuildPlan.of(Blueprints.well(), ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);
        FakeBotContext ctx = botAt(world, plan.stand())
                .give(Material.COBBLESTONE, 50)
                .give(Material.TORCH, 1);

        assertEquals(BuildSession.State.DONE, run(ctx, new BuildSession(schedule), 1500));
        assertTrue(allSolid(world, plan), "věnec studny stojí");
        assertTrue(world.traitsAt(plan.furnishing().get(0).pos()).solid(), "pochodeň studny");
    }

    /**
     * Plán ve vzduchu (nesrovnaná výška staveniště) se NESMÍ ohlásit jako
     * hotový. {@code PlaceBlockTask} hlásí „hotovo" i když blok vůbec
     * nepoložil (není se o co opřít), takže bez ověření proti světu by celý
     * plán proběhl jako řada no-opů a skončil stavem DONE nad prázdným
     * staveništěm – sídlo si pak připsalo studnu, která nikdy nestála.
     */
    @Test
    void floatingPlanEndsIncompleteInsteadOfDone() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BlockPos floating = new BlockPos(0, FLOOR_Y + 9, 0); // osm bloků nad terénem
        BuildPlan plan = BuildPlan.of(Blueprints.well(), floating, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);
        FakeBotContext ctx = botAt(world, plan.stand())
                .give(Material.COBBLESTONE, 50)
                .give(Material.TORCH, 1);

        assertEquals(BuildSession.State.INCOMPLETE,
                run(ctx, new BuildSession(schedule), 1500));
        assertTrue(plan.cells().stream().noneMatch(c -> world.traitsAt(c.pos()).solid()),
                "ve vzduchu nevznikl jediný blok");
    }

    @Test
    void marketStallBuildsWithRoofAndChest() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BuildPlan plan = BuildPlan.of(Blueprints.marketStall(), ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);
        FakeBotContext ctx = botAt(world, plan.stand())
                .give(Material.COBBLESTONE, 60)
                .give(Material.CHEST, 1);

        assertEquals(BuildSession.State.DONE, run(ctx, new BuildSession(schedule), 2000));
        assertTrue(allSolid(world, plan), "pult i střecha stojí");
        assertTrue(world.traitsAt(plan.furnishing().get(0).pos()).solid(), "truhla na zboží");
    }

    @Test
    void townHallBuildsWalledHallWithDoor() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BuildPlan plan = BuildPlan.of(Blueprints.townHall(), ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);
        FakeBotContext ctx = botAt(world, plan.stand())
                .give(Material.COBBLESTONE, 100)
                .give(Material.OAK_DOOR, 1)
                .give(Material.TORCH, 1);

        assertEquals(BuildSession.State.DONE, run(ctx, new BuildSession(schedule), 4000));
        assertTrue(allSolid(world, plan), "zdi i střecha radnice stojí");
        for (FurnishCell f : plan.furnishing()) {
            assertTrue(world.traitsAt(f.pos()).solid(), "vybavení radnice osazeno: " + f.kind());
        }
    }

    @Test
    void granaryBuildsWithDoubleChest() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BuildPlan plan = BuildPlan.of(Blueprints.granary(), ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);
        FakeBotContext ctx = botAt(world, plan.stand())
                .give(Material.COBBLESTONE, 500)
                .give(Material.OAK_DOOR, 1)
                .give(Material.TORCH, 1)
                .give(Material.CHEST, 2);

        assertEquals(BuildSession.State.DONE, run(ctx, new BuildSession(schedule), 3000));
        assertTrue(allSolid(world, plan), "skořápka sýpky stojí");
        long chests = plan.furnishing().stream()
                .filter(f -> f.kind() == FurnishKind.CHEST)
                .filter(f -> world.traitsAt(f.pos()).solid())
                .count();
        assertEquals(2, chests, "obě truhly osazené");
    }

    @Test
    void runsOutOfBlocksThenResumesAfterRefill() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BuildPlan plan = BuildPlan.of(Blueprints.house(), ORIGIN, Cardinal.NORTH);
        FakeBotContext ctx = botAt(world, plan.stand()).give(Material.COBBLESTONE, 10);

        // Málo bloků → stavba se zablokuje na materiálu, něco už stojí.
        BuildSession.State first = run(ctx,
                new BuildSession(BuildPlanner.schedule(plan, world)), 3000);
        assertEquals(BuildSession.State.BLOCKED_MATERIAL, first);
        int placedAfterFirst = ctx.placed();
        assertTrue(placedAfterFirst >= 1 && placedAfterFirst <= 12,
                "položilo se ~10 bloků, ne víc: " + placedAfterFirst);
        assertTrue(placedAfterFirst < plan.cells().size(), "stavba není hotová");

        // Doplnit materiál → nová session přeskočí hotové a dostaví.
        ctx.give(Material.COBBLESTONE, 500)
                .give(Material.OAK_DOOR, 1).give(Material.TORCH, 1).give(Material.RED_BED, 1);
        BuildSession.State second = run(ctx,
                new BuildSession(BuildPlanner.schedule(plan, world)), 3000);
        assertEquals(BuildSession.State.DONE, second);
        assertTrue(allSolid(world, plan), "po doplnění je dům hotový");
    }

    @Test
    void resumeSkipsAlreadyBuiltBlocks() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BuildPlan plan = BuildPlan.of(Blueprints.house(), ORIGIN, Cardinal.NORTH);
        // Půlku bloků „už postavíme" přímo do světa.
        var cells = plan.cells();
        int half = cells.size() / 2;
        for (int i = 0; i < half; i++) {
            BlockPos p = cells.get(i).pos();
            world.set(p.x(), p.y(), p.z(), FakeWorldView.SOLID);
        }
        FakeBotContext ctx = botAt(world, plan.stand())
                .give(Material.COBBLESTONE, 500)
                .give(Material.OAK_DOOR, 1).give(Material.TORCH, 1).give(Material.RED_BED, 1);

        assertEquals(BuildSession.State.DONE,
                run(ctx, new BuildSession(BuildPlanner.schedule(plan, world)), 3000));
        assertTrue(allSolid(world, plan), "dostavěno");
        // Položilo se míň bloků, než má dům – zbytek už stál.
        assertTrue(ctx.placed() <= cells.size() - half + plan.furnishing().size(),
                "hotové bloky se nekladly znovu");
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
