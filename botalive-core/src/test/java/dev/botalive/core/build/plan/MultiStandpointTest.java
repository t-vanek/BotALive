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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Velký dům (7×7) nejde postavit z jednoho místa – planner ho rozdělí na víc
 * stanovišť a {@code BuildSession} mezi nimi přechází. Ověřuje se, že každý
 * blok padne na stanoviště, ze kterého na něj bot dosáhne, pořadí drží oporu
 * i napříč jednotkami, a stavba se z těch stanovišť opravdu celá postaví.
 */
class MultiStandpointTest {

    private static final int FLOOR_Y = 63;
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    private static final double REACH_ASSIGN = 4.3;

    private static boolean reaches(BlockPos stand, BlockPos cell) {
        Vec3 eye = new Vec3(stand.x() + 0.5, stand.y() + 1.62, stand.z() + 0.5);
        Vec3 center = new Vec3(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5);
        return eye.distanceSquared(center) <= REACH_ASSIGN * REACH_ASSIGN;
    }

    @Test
    void smallHouseStaysSingleUnitLargeHouseSplits() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        var small = BuildPlanner.schedule(
                BuildPlan.of(new HouseGenerator(5, 3), ORIGIN, Cardinal.NORTH), world);
        assertEquals(1, small.units().size(), "5×5 se postaví z jednoho stanoviště");

        var large = BuildPlanner.schedule(
                BuildPlan.of(new HouseGenerator(7, 3), ORIGIN, Cardinal.NORTH), world);
        assertTrue(large.units().size() > 1, "7×7 vyžaduje víc stanovišť");
    }

    @Test
    void everyCellAssignedToAReachingStandExactlyOnce() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BuildPlan plan = BuildPlan.of(new HouseGenerator(7, 3), ORIGIN, Cardinal.NORTH);
        var schedule = BuildPlanner.schedule(plan, world);

        Set<Long> covered = new HashSet<>();
        for (WorkUnit unit : schedule.units()) {
            for (PlacementCell cell : unit.placements()) {
                assertTrue(reaches(unit.stand(), cell.pos()),
                        "blok " + cell.pos() + " je mimo dosah stanoviště " + unit.stand());
                assertTrue(covered.add(cell.pos().asLong()),
                        "blok se nesmí stavět dvakrát: " + cell.pos());
            }
        }
        assertEquals(plan.cells().size(), covered.size(), "každý blok má stanoviště");
    }

    @Test
    void supportHoldsAcrossUnitOrder() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BuildPlan plan = BuildPlan.of(new HouseGenerator(7, 3), ORIGIN, Cardinal.NORTH);
        var schedule = BuildPlanner.schedule(plan, world);

        Set<Long> solid = new HashSet<>();
        plan.groundColumns().forEach(g -> solid.add(g.asLong()));
        for (WorkUnit unit : schedule.units()) {
            for (PlacementCell cell : unit.placements()) {
                BlockPos p = cell.pos();
                boolean supported = solid.contains(p.down().asLong())
                        || solid.contains(p.up().asLong())
                        || solid.contains(p.offset(1, 0, 0).asLong())
                        || solid.contains(p.offset(-1, 0, 0).asLong())
                        || solid.contains(p.offset(0, 0, 1).asLong())
                        || solid.contains(p.offset(0, 0, -1).asLong());
                assertTrue(supported, "blok " + p + " nemá při pokládce oporu (napříč jednotkami)");
                solid.add(p.asLong());
            }
        }
    }

    @Test
    void sessionBuildsLargeHouseAcrossStandpoints() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        HouseGenerator gen = new HouseGenerator(7, 3);
        Palette palette = PaletteResolver.resolve(Material.OAK_LOG, 3);
        BuildPlan plan = BuildPlan.of(gen, ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);
        assertTrue(schedule.units().size() > 1, "test má smysl jen pro víc stanovišť");

        FakeBotContext ctx = new FakeBotContext(world, personality());
        ctx.give(Material.OAK_PLANKS, 600).give(Material.OAK_LOG, 100)
                .give(Material.GLASS, 40).give(Material.COBBLESTONE, 600)
                .give(Material.STONE_BRICKS, 300).give(Material.STONE, 200)
                .give(Material.OAK_DOOR, 1).give(Material.RED_BED, 1).give(Material.TORCH, 1);

        BuildSession session = new BuildSession(schedule, palette);
        BuildSession.State state = BuildSession.State.RUNNING;
        for (int i = 0; i < 30000 && state == BuildSession.State.RUNNING; i++) {
            // Simulace navigace: bot je vždy na stanovišti, kam session míří.
            BlockPos s = session.currentStand();
            ctx.update(new Vec3(s.x() + 0.5, s.y(), s.z() + 0.5), true);
            state = session.tick(ctx);
        }
        assertEquals(BuildSession.State.DONE, state, "velký dům se dostaví");
        for (PlacementCell cell : plan.cells()) {
            assertTrue(world.traitsAt(cell.pos()).solid(), "blok stojí: " + cell.pos());
            assertTrue(AcceptancePolicy.accepts(cell.spec().role(),
                    world.materialAt(cell.pos()), palette),
                    "materiál sedí roli " + cell.spec().role() + " na " + cell.pos());
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
