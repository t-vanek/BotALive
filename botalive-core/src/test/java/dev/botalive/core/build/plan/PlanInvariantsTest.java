package dev.botalive.core.build.plan;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jádro jistoty „co projde plannerem, to bot postaví": pořadí pokládky má
 * pro každý blueprint vždy oporu (jako {@code PortalBlueprintTest}), nic se
 * neztratí ani nezdvojí, dveře zůstanou průchozí a terraform odpovídá světu.
 */
class PlanInvariantsTest {

    private static final BlockPos ORIGIN = new BlockPos(8, 64, -4);

    private static List<Blueprint> blueprints() {
        return List.of(Blueprints.house(), Blueprints.well(), Blueprints.granary(),
                Blueprints.marketStall(), Blueprints.townHall(), Blueprints.church(),
                Blueprints.bellTower());
    }

    /** Nezávislá kontrola opory (nepoužívá interní BuildPlanner). */
    private static boolean supported(BlockPos pos, Set<Long> solid) {
        return solid.contains(pos.down().asLong())
                || solid.contains(pos.up().asLong())
                || solid.contains(pos.offset(1, 0, 0).asLong())
                || solid.contains(pos.offset(-1, 0, 0).asLong())
                || solid.contains(pos.offset(0, 0, 1).asLong())
                || solid.contains(pos.offset(0, 0, -1).asLong());
    }

    @Test
    void everyPlacementHasSupportWhenPlaced() {
        for (Blueprint bp : blueprints()) {
            for (Cardinal facing : Cardinal.values()) {
                List<PlacementCell> ordered =
                        BuildPlanner.order(bp.cells(ORIGIN, facing), bp.groundColumns(ORIGIN, facing));
                Set<Long> solid = new HashSet<>();
                for (BlockPos ground : bp.groundColumns(ORIGIN, facing)) {
                    solid.add(ground.asLong());
                }
                for (PlacementCell cell : ordered) {
                    assertTrue(supported(cell.pos(), solid),
                            "blok " + cell.pos() + " nemá při pokládce oporu ("
                                    + bp + ", " + facing + ")");
                    solid.add(cell.pos().asLong());
                }
            }
        }
    }

    @Test
    void orderingPreservesEveryCellExactlyOnce() {
        for (Blueprint bp : blueprints()) {
            for (Cardinal facing : Cardinal.values()) {
                List<PlacementCell> cells = bp.cells(ORIGIN, facing);
                List<PlacementCell> ordered =
                        BuildPlanner.order(cells, bp.groundColumns(ORIGIN, facing));
                assertEquals(cells.size(), ordered.size(), "žádný blok se neztratí");
                Set<Long> in = new HashSet<>();
                cells.forEach(c -> in.add(c.pos().asLong()));
                Set<Long> out = new HashSet<>();
                ordered.forEach(c -> assertTrue(out.add(c.pos().asLong()),
                        "blok se v pořadí nesmí opakovat"));
                assertEquals(in, out, "množina bloků se zachová");
            }
        }
    }

    @Test
    void doorwayStaysClearOfPlacements() {
        Blueprint house = Blueprints.house();
        for (Cardinal facing : Cardinal.values()) {
            Set<Long> placed = new HashSet<>();
            house.cells(ORIGIN, facing).forEach(c -> placed.add(c.pos().asLong()));
            BlockPos door = house.doorCell(ORIGIN, facing).orElseThrow();
            assertFalse(placed.contains(door.asLong()), "dveřní otvor se nezazdí (" + facing + ")");
        }
    }

    @Test
    void scheduleDigsObstaclesAndFillsFloorHoles() {
        // Podlaha na y=63 (pod originem 64) je pevná; vyhloubíme jednu díru
        // a vložíme jeden balvan do objemu stavby.
        FakeWorldView world = new FakeWorldView(63);
        BlockPos hole = ORIGIN.offset(1, -1, 1);           // díra v podlaze
        world.set(hole.x(), hole.y(), hole.z(), FakeWorldView.AIRLIKE);
        BlockPos boulder = ORIGIN.offset(2, 1, 2);          // balvan v objemu
        world.set(boulder.x(), boulder.y(), boulder.z(), FakeWorldView.SOLID);

        BuildPlan plan = BuildPlan.of(Blueprints.house(), ORIGIN, Cardinal.NORTH);
        BuildSchedule schedule = BuildPlanner.schedule(plan, world);

        assertTrue(schedule.fill().contains(hole), "díra v podlaze se zasype");
        assertTrue(schedule.mine().contains(boulder), "balvan v objemu se vytěží");
        // Balvan není součást stavby, díra ano-li? Zásyp jen tam, kde chybí podlaha.
        assertEquals(1, schedule.fill().size(), "zasype se jen skutečná díra");
        // Bloky stavby se netěží (i kdyby byly omylem pevné).
        Set<Long> structure = new HashSet<>();
        plan.cells().forEach(c -> structure.add(c.pos().asLong()));
        schedule.mine().forEach(m ->
                assertFalse(structure.contains(m.asLong()), "vlastní stavba se nebourá"));
    }

    @Test
    void hangingBlockIsRejected() {
        // Blok ve vzduchu bez jakéhokoli souseda → neproveditelné.
        List<PlacementCell> floating = List.of(
                new PlacementCell(new BlockPos(0, 100, 0), BlockSpec.GENERIC));
        assertThrows(UnsupportableBlueprintException.class,
                () -> BuildPlanner.order(floating, new ArrayList<>()));
    }
}
