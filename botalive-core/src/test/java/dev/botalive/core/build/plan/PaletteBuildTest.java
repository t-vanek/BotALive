package dev.botalive.core.build.plan;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.testutil.FakeBotContext;
import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link BuildSession} s paletou klade materiál <b>podle role</b>: zeď z prken,
 * okno ze skla. Ověřuje se přes {@code materialAt} syntetického světa (fake
 * akce zaznamenávají, co bot skutečně položil).
 */
class PaletteBuildTest {

    @Test
    void sessionPlacesMaterialPerRole() {
        FakeWorldView world = new FakeWorldView(63);
        BlockPos wallA = new BlockPos(0, 64, 0);
        BlockPos window = new BlockPos(1, 64, 0);
        BlockPos wallB = new BlockPos(2, 64, 0);
        BlockPos stand = new BlockPos(1, 64, -1);

        BuildPlan plan = new BuildPlan(
                List.of(
                        new PlacementCell(wallA, BlockSpec.of(PaletteRole.WALL)),
                        new PlacementCell(window, BlockSpec.of(PaletteRole.WINDOW)),
                        new PlacementCell(wallB, BlockSpec.of(PaletteRole.WALL))),
                List.of(wallA, window, wallB),                       // clearVolume
                List.of(wallA.down(), window.down(), wallB.down()),  // groundColumns (floor)
                List.of(), stand, Optional.empty(), false);

        Palette palette = PaletteResolver.resolve(Material.SPRUCE_LOG, 5);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);
        FakeBotContext ctx = new FakeBotContext(world, personality());
        ctx.update(new Vec3(stand.x() + 0.5, stand.y(), stand.z() + 0.5), true);
        ctx.give(Material.SPRUCE_PLANKS, 10).give(Material.GLASS, 10);

        BuildSession session = new BuildSession(schedule, palette);
        BuildSession.State state = BuildSession.State.RUNNING;
        for (int i = 0; i < 500 && state == BuildSession.State.RUNNING; i++) {
            state = session.tick(ctx);
        }
        assertEquals(BuildSession.State.DONE, state);
        assertEquals(Material.SPRUCE_PLANKS, world.materialAt(wallA), "zeď z prken");
        assertEquals(Material.SPRUCE_PLANKS, world.materialAt(wallB), "zeď z prken");
        assertEquals(Material.GLASS, world.materialAt(window), "okno ze skla");
    }

    @Test
    void substitutesBuildingBlockWhenPaletteMaterialMissing() {
        FakeWorldView world = new FakeWorldView(63);
        BlockPos wall = new BlockPos(0, 64, 0);
        BlockPos stand = new BlockPos(0, 64, -1);
        BuildPlan plan = new BuildPlan(
                List.of(new PlacementCell(wall, BlockSpec.of(PaletteRole.WALL))),
                List.of(wall), List.of(wall.down()),
                List.of(), stand, Optional.empty(), false);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);
        FakeBotContext ctx = new FakeBotContext(world, personality());
        ctx.update(new Vec3(stand.x() + 0.5, stand.y(), stand.z() + 0.5), true);
        // Prkna z palety nemá, jen zaměnitelný blok → náhrada, stavba nezasekne.
        ctx.give(Material.COBBLESTONE, 10);

        BuildSession session = new BuildSession(schedule,
                PaletteResolver.resolve(Material.OAK_LOG, 1));
        BuildSession.State state = BuildSession.State.RUNNING;
        for (int i = 0; i < 200 && state == BuildSession.State.RUNNING; i++) {
            state = session.tick(ctx);
        }
        assertEquals(BuildSession.State.DONE, state);
        assertEquals(Material.COBBLESTONE, world.materialAt(wall), "náhradní blok");
    }

    @Test
    void leavesWindowOpenWhenGlassMissing() {
        FakeWorldView world = new FakeWorldView(63);
        BlockPos wallA = new BlockPos(0, 64, 0);
        BlockPos window = new BlockPos(1, 64, 0);
        BlockPos wallB = new BlockPos(2, 64, 0);
        BlockPos stand = new BlockPos(1, 64, -1);

        BuildPlan plan = new BuildPlan(
                List.of(
                        new PlacementCell(wallA, BlockSpec.of(PaletteRole.WALL)),
                        new PlacementCell(window, BlockSpec.of(PaletteRole.WINDOW)),
                        new PlacementCell(wallB, BlockSpec.of(PaletteRole.WALL))),
                List.of(wallA, window, wallB),
                List.of(wallA.down(), window.down(), wallB.down()),
                List.of(), stand, Optional.empty(), false);

        Palette palette = PaletteResolver.resolve(Material.SPRUCE_LOG, 5);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);
        FakeBotContext ctx = new FakeBotContext(world, personality());
        ctx.update(new Vec3(stand.x() + 0.5, stand.y(), stand.z() + 0.5), true);
        // Prkna na zeď má, sklo na okno NE → okno se nechá otvorem, ne zazdí.
        ctx.give(Material.SPRUCE_PLANKS, 10);

        BuildSession session = new BuildSession(schedule, palette);
        BuildSession.State state = BuildSession.State.RUNNING;
        for (int i = 0; i < 500 && state == BuildSession.State.RUNNING; i++) {
            state = session.tick(ctx);
        }
        // Stavba doběhne (otvor není torzo), zdi stojí, okno zůstane vzduchem.
        assertEquals(BuildSession.State.DONE, state, "otvor okna není nedokončená stavba");
        assertEquals(Material.SPRUCE_PLANKS, world.materialAt(wallA), "zeď z prken");
        assertEquals(Material.SPRUCE_PLANKS, world.materialAt(wallB), "zeď z prken");
        assertEquals(Material.AIR, world.materialAt(window), "okno bez skla je otvor");
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
