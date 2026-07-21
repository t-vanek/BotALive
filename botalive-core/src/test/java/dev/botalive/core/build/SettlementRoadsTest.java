package dev.botalive.core.build;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy plánovače silniční sítě sídla – geometrie hlavních ulic, městský
 * okruh, idempotentní dusání jen po trávě a strop kroků.
 */
class SettlementRoadsTest {

    private static final BlockPos CENTER = new BlockPos(0, 64, 0);
    private static final int SPACING = 12;

    /** Rovná travnatá pláň o poloměru {@code r} kolem návsi. */
    private static FakeWorldView grassField(int r) {
        FakeWorldView world = new FakeWorldView(64);
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                world.set(x, 64, z, Material.GRASS_BLOCK, FakeWorldView.SOLID);
            }
        }
        return world;
    }

    private static Set<BlockPos> targets(List<VillageDecor.Step> steps) {
        // Síť je vždy jen dusání (path), nikdy pochodně.
        assertTrue(steps.stream().allMatch(VillageDecor.Step::path));
        return steps.stream().map(VillageDecor.Step::target).collect(Collectors.toSet());
    }

    @Test
    void bezParcelZadnaSit() {
        assertTrue(SettlementRoads.plan(grassField(20), CENTER, SPACING,
                List.of(), false, 40).isEmpty());
    }

    @Test
    void jednaParcelaVedeUliciKNavsi() {
        // Parcela východně (buňka 1,0): hlavní ulice x=0..12 v z=0.
        BlockPos east = new BlockPos(SPACING - 2, 64, -2); // origin buňky (1,0)
        List<VillageDecor.Step> steps = SettlementRoads.plan(grassField(20), CENTER,
                SPACING, List.of(east), false, 40);
        Set<BlockPos> targets = targets(steps);
        assertEquals(13, targets.size());
        assertTrue(targets.contains(new BlockPos(0, 64, 0)));
        assertTrue(targets.contains(new BlockPos(6, 64, 0)));
        assertTrue(targets.contains(new BlockPos(12, 64, 0)));
    }

    @Test
    void protilehleParcelySpojiPrubeznaUlice() {
        BlockPos east = new BlockPos(SPACING - 2, 64, -2);   // (1,0)
        BlockPos west = new BlockPos(-SPACING - 2, 64, -2);  // (-1,0)
        Set<BlockPos> targets = targets(SettlementRoads.plan(grassField(20), CENTER,
                SPACING, List.of(east, west), false, 40));
        assertEquals(25, targets.size()); // x -12..12 v z=0
        assertTrue(targets.contains(new BlockPos(-12, 64, 0)));
        assertTrue(targets.contains(new BlockPos(12, 64, 0)));
    }

    @Test
    void vzdalenaParcelaMaUliciAZebro() {
        // Buňka (2,1): ulice po X k x=24, pak žebro po Z k z=12.
        BlockPos plot = new BlockPos(2 * SPACING - 2, 64, SPACING - 2);
        Set<BlockPos> targets = targets(SettlementRoads.plan(grassField(40), CENTER,
                SPACING, List.of(plot), false, 40));
        assertEquals(37, targets.size()); // 25 (ulice) + 13 (žebro) - 1 sdílený roh
        assertTrue(targets.contains(new BlockPos(24, 64, 0)));   // konec ulice
        assertTrue(targets.contains(new BlockPos(24, 64, 6)));   // žebro
        assertTrue(targets.contains(new BlockPos(24, 64, 12)));  // u domu
        assertTrue(targets.contains(new BlockPos(0, 64, 0)));    // náves
    }

    @Test
    void hotovaCestaSeNeplanujeZnovu() {
        // Idempotence: udusaný blok (už DIRT_PATH, ne tráva) se přeskočí.
        FakeWorldView world = grassField(20);
        world.set(6, 64, 0, Material.DIRT_PATH, FakeWorldView.PATH);
        BlockPos east = new BlockPos(SPACING - 2, 64, -2);
        Set<BlockPos> targets = targets(SettlementRoads.plan(world, CENTER, SPACING,
                List.of(east), false, 40));
        assertEquals(12, targets.size());
        assertFalse(targets.contains(new BlockPos(6, 64, 0)));
    }

    @Test
    void navesSePodlahouDomuNedusa() {
        // Náves leží na domě zakladatele (ne tráva) – filtr ji přeskočí.
        FakeWorldView world = grassField(20);
        world.set(0, 64, 0, Material.OAK_PLANKS, FakeWorldView.SOLID);
        BlockPos east = new BlockPos(SPACING - 2, 64, -2);
        Set<BlockPos> targets = targets(SettlementRoads.plan(world, CENTER, SPACING,
                List.of(east), false, 40));
        assertEquals(12, targets.size()); // x=1..12, náves (0,0) vynechána
        assertFalse(targets.contains(new BlockPos(0, 64, 0)));
    }

    @Test
    void mestoDostaneObvodovyOkruh() {
        // Čtyři osové parcely prstence 1; okruh přidá rohy, které samotné ulice nemají.
        List<BlockPos> plots = List.of(
                new BlockPos(SPACING - 2, 64, -2),    // (1,0)
                new BlockPos(-SPACING - 2, 64, -2),   // (-1,0)
                new BlockPos(-2, 64, SPACING - 2),    // (0,1)
                new BlockPos(-2, 64, -SPACING - 2));  // (0,-1)
        Set<BlockPos> withoutRing = targets(SettlementRoads.plan(grassField(30), CENTER,
                SPACING, plots, false, 200));
        assertFalse(withoutRing.contains(new BlockPos(12, 64, 12)),
                "bez okruhu roh není součástí kříže ulic");

        Set<BlockPos> withRing = targets(SettlementRoads.plan(grassField(30), CENTER,
                SPACING, plots, true, 200));
        assertTrue(withRing.contains(new BlockPos(12, 64, 12)), "okruh: roh");
        assertTrue(withRing.contains(new BlockPos(-12, 64, -12)), "okruh: protilehlý roh");
        assertTrue(withRing.contains(new BlockPos(0, 64, 12)), "okruh: střed hrany");
    }

    @Test
    void stropKrokuDrziSeanciKratkou() {
        List<BlockPos> plots = List.of(
                new BlockPos(SPACING - 2, 64, -2),
                new BlockPos(-SPACING - 2, 64, -2),
                new BlockPos(-2, 64, SPACING - 2),
                new BlockPos(-2, 64, -SPACING - 2));
        List<VillageDecor.Step> steps = SettlementRoads.plan(grassField(30), CENTER,
                SPACING, plots, true, 5);
        assertEquals(5, steps.size());
    }

    @Test
    void krokySeradiOdNavsiVen() {
        // Bez ohledu na strop se dusá od středu – rozdělaná síť vypadá záměrně.
        BlockPos plot = new BlockPos(3 * SPACING - 2, 64, -2); // (3,0)
        List<VillageDecor.Step> steps = SettlementRoads.plan(grassField(60), CENTER,
                SPACING, List.of(plot), false, 3);
        assertEquals(Set.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0)), targets(steps));
    }
}
