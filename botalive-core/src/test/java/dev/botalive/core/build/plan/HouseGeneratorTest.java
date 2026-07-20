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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generovaný dům musí být <b>postavitelný</b> (opora při pokládce, celý na
 * dosah z jednoho stanoviště) a po stavbě mít materiály podle rolí (zeď prkna,
 * okno sklo, nároží kmen, střecha krytina). Reach-invariant je pojistka, že
 * výchozí velikost 5×5 zvládne jeden stavitel bez přesouvání.
 */
class HouseGeneratorTest {

    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    /** Dosah ruky na blok (vanilla ~4,5). */
    private static final double REACH = 4.5;

    @Test
    void everyCellReachableFromSingleStand() {
        HouseGenerator gen = new HouseGenerator(5, 3);
        for (Cardinal facing : Cardinal.values()) {
            BlockPos stand = gen.standPoint(ORIGIN, facing);
            Vec3 eye = new Vec3(stand.x() + 0.5, stand.y() + 1.62, stand.z() + 0.5);
            for (PlacementCell cell : gen.cells(ORIGIN, facing)) {
                double d = nearestDistance(eye, cell.pos());
                assertTrue(d <= REACH,
                        "blok " + cell.pos() + " je z " + stand + " daleko " + d
                                + " (" + facing + ")");
            }
        }
    }

    @Test
    void orderingHasSupportForEveryCell() {
        HouseGenerator gen = new HouseGenerator(5, 3);
        for (Cardinal facing : Cardinal.values()) {
            List<PlacementCell> ordered = BuildPlanner.order(
                    gen.cells(ORIGIN, facing), gen.groundColumns(ORIGIN, facing));
            Set<Long> solid = new HashSet<>();
            gen.groundColumns(ORIGIN, facing).forEach(g -> solid.add(g.asLong()));
            for (PlacementCell cell : ordered) {
                assertTrue(hasNeighbor(cell.pos(), solid),
                        "blok " + cell.pos() + " nemá při pokládce oporu");
                solid.add(cell.pos().asLong());
            }
        }
    }

    @Test
    void hasWindowsAccentsFoundationAndRoof() {
        List<PlacementCell> cells = new HouseGenerator(5, 3).cells(ORIGIN, Cardinal.NORTH);
        assertEquals(3, roleCount(cells, PaletteRole.WINDOW), "tři okna");
        assertEquals(8, roleCount(cells, PaletteRole.WALL_ACCENT), "nároží ve dvou vrstvách zdi");
        assertTrue(roleCount(cells, PaletteRole.FOUNDATION) > 0, "kamenná obruba");
        assertTrue(roleCount(cells, PaletteRole.ROOF) > 0, "střecha");
        assertTrue(roleCount(cells, PaletteRole.WALL) > 0, "zdivo");
    }

    @Test
    void buildsFullyWithMaterialsPerRole() {
        FakeWorldView world = new FakeWorldView(63);
        HouseGenerator gen = new HouseGenerator(5, 3);
        Palette palette = PaletteResolver.resolve(Material.OAK_LOG, 11);
        BuildPlan plan = BuildPlan.of(gen, ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);

        BlockPos stand = plan.stand();
        FakeBotContext ctx = new FakeBotContext(world, personality());
        ctx.update(new Vec3(stand.x() + 0.5, stand.y(), stand.z() + 0.5), true);
        // Všechny možné materiály palety (aby nedošlo k náhradě).
        ctx.give(Material.OAK_PLANKS, 300).give(Material.OAK_LOG, 60)
                .give(Material.GLASS, 30).give(Material.COBBLESTONE, 300)
                .give(Material.STONE_BRICKS, 200).give(Material.STONE, 100)
                .give(Material.OAK_DOOR, 1).give(Material.RED_BED, 1).give(Material.TORCH, 1);

        BuildSession session = new BuildSession(schedule, palette);
        BuildSession.State state = BuildSession.State.RUNNING;
        for (int i = 0; i < 6000 && state == BuildSession.State.RUNNING; i++) {
            state = session.tick(ctx);
        }
        assertEquals(BuildSession.State.DONE, state, "dům se dostaví");
        for (PlacementCell cell : plan.cells()) {
            assertTrue(world.traitsAt(cell.pos()).solid(),
                    "blok stojí: " + cell.pos());
            assertTrue(AcceptancePolicy.accepts(cell.spec().role(),
                            world.materialAt(cell.pos()), palette),
                    "materiál sedí roli " + cell.spec().role() + " na " + cell.pos()
                            + " (je " + world.materialAt(cell.pos()) + ")");
        }
    }

    // ------------------------------------------------------------------ pomocné

    private static int roleCount(List<PlacementCell> cells, PaletteRole role) {
        return (int) cells.stream().filter(c -> c.spec().role() == role).count();
    }

    private static boolean hasNeighbor(BlockPos pos, Set<Long> solid) {
        return solid.contains(pos.down().asLong()) || solid.contains(pos.up().asLong())
                || solid.contains(pos.offset(1, 0, 0).asLong())
                || solid.contains(pos.offset(-1, 0, 0).asLong())
                || solid.contains(pos.offset(0, 0, 1).asLong())
                || solid.contains(pos.offset(0, 0, -1).asLong());
    }

    /** Vzdálenost oka k nejbližšímu bodu AABB bloku (buňka [p, p+1]). */
    private static double nearestDistance(Vec3 eye, BlockPos cell) {
        double nx = clamp(eye.x(), cell.x(), cell.x() + 1);
        double ny = clamp(eye.y(), cell.y(), cell.y() + 1);
        double nz = clamp(eye.z(), cell.z(), cell.z() + 1);
        double dx = eye.x() - nx;
        double dy = eye.y() - ny;
        double dz = eye.z() - nz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
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
