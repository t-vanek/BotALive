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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lešení: vysokou stavbu (dutá věž) nejde postavit ze země – planner musí
 * vygenerovat <b>vyvýšená pilířová stanoviště</b>, aby na horní bloky bot
 * dosáhl, a k nim dočasné lešení k pozdějšímu úklidu. Ověřuje se, že každý
 * blok padne na dosažitelné stanoviště, věž si vyžádá stanoviště nad zemí,
 * lešení stojí ve volném vnitřním sloupci a drží opora napříč jednotkami.
 */
class ScaffoldPlanTest {

    private static final int FLOOR_Y = 63;
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    private static final double REACH_ASSIGN = 4.3;

    private static boolean reaches(BlockPos stand, BlockPos cell) {
        Vec3 eye = new Vec3(stand.x() + 0.5, stand.y() + 1.62, stand.z() + 0.5);
        Vec3 center = new Vec3(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5);
        return eye.distanceSquared(center) <= REACH_ASSIGN * REACH_ASSIGN;
    }

    /** Dutá věž 3×3 o dané výšce, otevřený vršek – testovací blueprint. */
    private static Blueprint tower(int height) {
        return new Blueprint() {
            @Override
            public List<PlacementCell> cells(BlockPos o, Cardinal f) {
                List<PlacementCell> c = new ArrayList<>();
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < 3; x++) {
                        for (int z = 0; z < 3; z++) {
                            if (x == 0 || x == 2 || z == 0 || z == 2) {
                                c.add(new PlacementCell(o.offset(x, y, z), BlockSpec.GENERIC));
                            }
                        }
                    }
                }
                return c;
            }

            @Override
            public List<BlockPos> clearVolume(BlockPos o, Cardinal f) {
                List<BlockPos> v = new ArrayList<>();
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < 3; x++) {
                        for (int z = 0; z < 3; z++) {
                            v.add(o.offset(x, y, z));
                        }
                    }
                }
                return v;
            }

            @Override
            public List<BlockPos> groundColumns(BlockPos o, Cardinal f) {
                List<BlockPos> g = new ArrayList<>();
                for (int x = 0; x < 3; x++) {
                    for (int z = 0; z < 3; z++) {
                        g.add(o.offset(x, -1, z));
                    }
                }
                return g;
            }

            @Override
            public List<FurnishCell> furnishing(BlockPos o, Cardinal f) {
                return List.of();
            }

            @Override
            public BlockPos standPoint(BlockPos o, Cardinal f) {
                return o.offset(1, 0, 1);
            }

            @Override
            public Optional<BlockPos> doorCell(BlockPos o, Cardinal f) {
                return Optional.empty();
            }

            @Override
            public int blocksNeeded() {
                return 8 * height;
            }

            @Override
            public boolean standExact() {
                return true;
            }
        };
    }

    @Test
    void everyTowerCellReachesAStandExactlyOnce() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BuildPlan plan = BuildPlan.of(tower(10), ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);

        Set<Long> covered = new HashSet<>();
        for (WorkUnit unit : schedule.units()) {
            for (PlacementCell cell : unit.placements()) {
                assertTrue(reaches(unit.stand(), cell.pos()),
                        "blok " + cell.pos() + " je mimo dosah stanoviště " + unit.stand());
                assertTrue(covered.add(cell.pos().asLong()),
                        "blok se nesmí stavět dvakrát: " + cell.pos());
            }
        }
        assertEquals(plan.cells().size(), covered.size(), "každý blok věže má stanoviště");
    }

    @Test
    void tallTowerNeedsElevatedStandsAndScaffold() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BuildPlan plan = BuildPlan.of(tower(10), ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);

        boolean elevated = schedule.units().stream().anyMatch(u -> u.stand().y() > ORIGIN.y());
        assertTrue(elevated, "vysoká věž potřebuje stanoviště nad zemí (pilíř)");

        assertFalse(schedule.scaffold().isEmpty(), "vyvýšená stanoviště mají pod sebou lešení");
        // Lešení stojí ve volném vnitřním sloupci (střed 3×3), od podlahy nahoru.
        for (BlockPos s : schedule.scaffold()) {
            assertEquals(ORIGIN.x() + 1, s.x(), "lešení je ve vnitřním sloupci (x)");
            assertEquals(ORIGIN.z() + 1, s.z(), "lešení je ve vnitřním sloupci (z)");
            assertTrue(s.y() >= ORIGIN.y(), "lešení začíná na podlaze");
        }
    }

    @Test
    void lowStructureUsesNoScaffold() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        // Nízká věž na dosah ze země – žádné vyvýšené stanoviště, žádné lešení.
        BuildPlan plan = BuildPlan.of(tower(3), ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);

        assertTrue(schedule.scaffold().isEmpty(), "nízká stavba se staví ze země, bez lešení");
        assertTrue(schedule.units().stream().allMatch(u -> u.stand().y() == ORIGIN.y()),
                "všechna stanoviště jsou na podlaze");
    }

    @Test
    void sessionBuildsTallTowerFromElevatedStands() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BuildPlan plan = BuildPlan.of(tower(10), ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);
        assertTrue(schedule.units().stream().anyMatch(u -> u.stand().y() > ORIGIN.y()),
                "test má smysl jen s vyvýšenými stanovišti");

        FakeBotContext ctx = new FakeBotContext(world, personality());
        ctx.give(Material.COBBLESTONE, 500);

        BuildSession session = new BuildSession(schedule);
        BuildSession.State state = BuildSession.State.RUNNING;
        for (int i = 0; i < 30000 && state == BuildSession.State.RUNNING; i++) {
            // Simulace: bot je vždy na stanovišti, kam session míří (i vyvýšeném).
            BlockPos s = session.currentStand();
            ctx.update(new Vec3(s.x() + 0.5, s.y(), s.z() + 0.5), true);
            state = session.tick(ctx);
        }
        assertEquals(BuildSession.State.DONE, state, "věž se dostaví i z vyvýšených stanovišť");
        for (PlacementCell cell : plan.cells()) {
            assertTrue(world.traitsAt(cell.pos()).solid(), "blok věže stojí: " + cell.pos());
        }
    }

    @Test
    void veryTallTowerExceedsSinglePillarHeight() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        int height = 20; // nad strop jednoho pilíře (PillarUpTask.MAX_HEIGHT = 12)
        BuildPlan plan = BuildPlan.of(tower(height), ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);

        // Planner nabídne stanoviště až k vršku, ne jen do 12 – výšku určuje
        // stavba; pilíř vyšší než jeden úsek navigace vyleze nadvakrát.
        int topStand = schedule.units().stream().mapToInt(u -> u.stand().y()).max().orElseThrow();
        assertTrue(topStand > ORIGIN.y() + 12,
                "vysoká věž má stanoviště nad strop jednoho pilíře, ne oříznutá na 12");

        FakeBotContext ctx = new FakeBotContext(world, personality());
        ctx.give(Material.COBBLESTONE, 1000);
        BuildSession session = new BuildSession(schedule);
        BuildSession.State state = BuildSession.State.RUNNING;
        for (int i = 0; i < 60000 && state == BuildSession.State.RUNNING; i++) {
            BlockPos s = session.currentStand();
            ctx.update(new Vec3(s.x() + 0.5, s.y(), s.z() + 0.5), true);
            state = session.tick(ctx);
        }
        assertEquals(BuildSession.State.DONE, state, "i vysoká věž (>12) se dostaví");
        for (PlacementCell cell : plan.cells()) {
            assertTrue(world.traitsAt(cell.pos()).solid(), "blok vysoké věže stojí: " + cell.pos());
        }
    }

    @Test
    void scaffoldSupportHoldsAcrossUnitOrder() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BuildPlan plan = BuildPlan.of(tower(10), ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);

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
                assertTrue(supported, "blok " + p + " nemá při pokládce oporu");
                solid.add(p.asLong());
            }
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
