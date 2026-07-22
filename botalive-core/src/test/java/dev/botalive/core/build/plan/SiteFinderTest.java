package dev.botalive.core.build.plan;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Srovnání výšky staveniště podle terénu. Katastr vesnice rozdává parcely
 * s Y návsi ({@code PlotLayout.plotOrigin}), takže na svahu přijde návrh
 * klidně osm bloků nad zemí nebo pod ní – {@link SiteFinder} musí najít
 * skutečnou úroveň podlahy, jinak se plán rozvine do vzduchu a stavba
 * nikdy nedoběhne.
 */
class SiteFinderTest {

    /** Pevno do 62 včetně → podlaha (úroveň originu) je 63. */
    private static final int FLOOR_Y = 62;
    private static final int GROUND = FLOOR_Y + 1;

    @Test
    void lowersOriginToTerrainWhenPlotFloatsAboveGround() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        // Přesně případ z testovacího serveru: návrh osm bloků nad terénem.
        BlockPos suggested = new BlockPos(37, GROUND + 8, 1);

        BlockPos usable = SiteFinder.usableOrigin(world, Blueprints.well(), suggested,
                Cardinal.NORTH, true).orElseThrow();

        assertEquals(GROUND, usable.y(), "výška srovnaná podle terénu");
        assertEquals(suggested.x(), usable.x(), "půdorys se neposouvá");
        assertEquals(suggested.z(), usable.z(), "půdorys se neposouvá");
    }

    @Test
    void raisesOriginWhenPlotSitsUnderground() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BlockPos suggested = new BlockPos(10, GROUND - 7, 10);

        BlockPos usable = SiteFinder.usableOrigin(world, Blueprints.granary(), suggested,
                Cardinal.NORTH, true).orElseThrow();

        assertEquals(GROUND, usable.y(), "stavba vylezla na povrch");
    }

    @Test
    void keepsSuggestedOriginWhenItAlreadyMatchesTerrain() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BlockPos suggested = new BlockPos(4, GROUND, 4);

        BlockPos usable = SiteFinder.usableOrigin(world, Blueprints.house(), suggested,
                Cardinal.NORTH, true).orElseThrow();

        // Rozestavěná stavba musí dostat zpátky PŘESNĚ svůj origin, jinak by
        // se po návratu stavěla podruhé o kus vedle.
        assertEquals(suggested, usable, "sedící návrh se neposouvá");
    }

    @Test
    void rejectsFloodedSite() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        // Jezero přes celý půdorys – ani jedna výška nevyhovuje.
        for (int x = 37; x <= 39; x++) {
            for (int z = 1; z <= 3; z++) {
                for (int y = GROUND; y <= GROUND + 8; y++) {
                    world.set(x, y, z, FakeWorldView.WATER);
                }
            }
        }
        BlockPos suggested = new BlockPos(37, GROUND, 1);

        assertTrue(SiteFinder.usableOrigin(world, Blueprints.well(), suggested,
                Cardinal.NORTH, true).isEmpty(), "do vody se nestaví");
    }

    @Test
    void flatGroundCostsNothing() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BlockPos origin = new BlockPos(0, GROUND, 0);

        assertEquals(0, SiteFinder.cost(world, Blueprints.well(), origin,
                Cardinal.NORTH, Set.of(), true), "rovina bez úprav");
        assertEquals(0, SiteFinder.bestCost(world, Blueprints.well(), origin,
                Cardinal.NORTH, true), "nejlepší výška je ta na terénu");
    }

    @Test
    void refusesToTerraformWhenTerraformingIsOff() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        // Díra v podlaze: bez povolených úprav je staveniště nepoužitelné.
        world.set(0, FLOOR_Y, 0, FakeWorldView.AIRLIKE);
        BlockPos origin = new BlockPos(0, GROUND, 0);

        assertEquals(SiteFinder.COST_INVALID, SiteFinder.cost(world, Blueprints.well(),
                origin, Cardinal.NORTH, Set.of(), false), "zásyp zakázán");
        assertTrue(SiteFinder.cost(world, Blueprints.well(), origin,
                Cardinal.NORTH, Set.of(), true) > 0, "se zásypem to jde");
    }

    @Test
    void rejectsFloorOnHazard() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        // Magma pod jedním sloupcem podlahy: pevné, ale podlaha na hazardu se
        // nesmí stavět (dřív prošlo jako „pevno").
        world.set(1, FLOOR_Y, 1, FakeWorldView.MAGMA);
        BlockPos origin = new BlockPos(0, GROUND, 0);

        assertEquals(SiteFinder.COST_INVALID, SiteFinder.cost(world, Blueprints.townHall(),
                origin, Cardinal.NORTH, Set.of(), true), "podlaha na magmatu je zakázaná");
    }

    @Test
    void rejectsFireInsideBuildVolume() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        // Oheň je průchozí (ne pevný, ne tekutý) → dřív starými guardy prošel;
        // hazard ho teď v objemu odmítne.
        world.set(1, GROUND, 1, FakeWorldView.FIRE);
        BlockPos origin = new BlockPos(0, GROUND, 0);

        assertEquals(SiteFinder.COST_INVALID, SiteFinder.cost(world, Blueprints.townHall(),
                origin, Cardinal.NORTH, Set.of(), true), "oheň v objemu je zakázaný");
    }

    @Test
    void bigFootprintGetsProportionalTerraformBudget() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        // Pět děr v podlaze: nad fixní strop 4, ale pod škálovaný strop velkého
        // sálu (25 sloupců → strop 6).
        for (int x = 0; x < 5; x++) {
            world.set(x, FLOOR_Y, 0, FakeWorldView.AIRLIKE);
        }
        BlockPos origin = new BlockPos(0, GROUND, 0);

        assertTrue(SiteFinder.cost(world, Blueprints.townHall(), origin,
                Cardinal.NORTH, Set.of(), true) > 0, "velký sál srovná 5 děr");
    }

    @Test
    void smallFootprintKeepsTightTerraformBudget() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        // Pět děr v podlaze studny (9 sloupců → strop zůstává 4) → nepoužitelné.
        world.set(0, FLOOR_Y, 0, FakeWorldView.AIRLIKE);
        world.set(1, FLOOR_Y, 0, FakeWorldView.AIRLIKE);
        world.set(2, FLOOR_Y, 0, FakeWorldView.AIRLIKE);
        world.set(0, FLOOR_Y, 1, FakeWorldView.AIRLIKE);
        world.set(1, FLOOR_Y, 1, FakeWorldView.AIRLIKE);
        BlockPos origin = new BlockPos(0, GROUND, 0);

        assertEquals(SiteFinder.COST_INVALID, SiteFinder.cost(world, Blueprints.well(),
                origin, Cardinal.NORTH, Set.of(), true), "studna 5 děr nesrovná (strop 4)");
    }

    @Test
    void searchShiftsWhenExactSpotIsBlocked() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        // Zaplav přesný roh studny (3×3) vodou přes všechny výšky.
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                for (int y = GROUND - 2; y <= GROUND + 2; y++) {
                    world.set(x, y, z, FakeWorldView.WATER);
                }
            }
        }
        BlockPos suggested = new BlockPos(0, GROUND, 0);

        assertTrue(SiteFinder.usableOrigin(world, Blueprints.well(), suggested,
                Cardinal.NORTH, true).isEmpty(), "přesný roh je pod vodou");
        BlockPos found = SiteFinder.search(world, Blueprints.well(), suggested,
                Cardinal.NORTH, true, 3).orElseThrow();
        assertTrue(found.x() != suggested.x() || found.z() != suggested.z(),
                "search se posunul z podvodního rohu");
        assertEquals(GROUND, found.y(), "posunutý roh sedí na terénu");
    }

    @Test
    void footprintSpanIsLargestPlanDimension() {
        // Radius posunu se z něj a z rozestupu parcel počítá footprint-aware.
        assertEquals(3, SiteFinder.footprintSpan(Blueprints.well(), Cardinal.NORTH),
                "studna 3×3");
        assertEquals(5, SiteFinder.footprintSpan(Blueprints.townHall(), Cardinal.NORTH),
                "radnice 5×5");
        assertEquals(7, SiteFinder.footprintSpan(Blueprints.church(), Cardinal.NORTH),
                "kostel 5×7 → větší rozměr 7");
    }

    @Test
    void budgetLadiPrisnostTerraformu() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        world.set(1, GROUND, 1, FakeWorldView.SOLID); // balvan v objemu radnice (1 výkop)
        BlockPos origin = new BlockPos(0, GROUND, 0);

        // Default rozpočet balvan vytěží (výkopy povolené).
        assertTrue(SiteFinder.cost(world, Blueprints.townHall(), origin, Cardinal.NORTH,
                Set.of(), true) >= 0, "default balvan srovná");
        // Přísný rozpočet: žádné výkopy (floor 0, obří divisor → škálovaný 0).
        SiteFinder.Budget strict = new SiteFinder.Budget(24, 4, 0, 4, 10_000);
        assertEquals(SiteFinder.COST_INVALID, SiteFinder.cost(world, Blueprints.townHall(),
                origin, Cardinal.NORTH, Set.of(), true, strict),
                "přísný rozpočet balvan odmítne");
    }

    @Test
    void footprintDimsAreWidthAndDepth() {
        int[] church = SiteFinder.footprintDims(Blueprints.church(), Cardinal.NORTH);
        assertEquals(5, church[0], "kostel šířka X = 5");
        assertEquals(7, church[1], "kostel hloubka Z = 7");
        int[] hall = SiteFinder.footprintDims(Blueprints.townHall(), Cardinal.NORTH);
        assertEquals(5, hall[0], "radnice 5×5");
        assertEquals(5, hall[1], "radnice 5×5");
    }

    @Test
    void searchSnapsToExactOriginForResume() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        BlockPos suggested = new BlockPos(10, GROUND, 10);
        // Sedící přesný roh se nesmí nikam posunout – rozestavěná stavba se
        // trefí do svého originu a naváže world-diffem.
        BlockPos found = SiteFinder.search(world, Blueprints.house(), suggested,
                Cardinal.NORTH, true, 3).orElseThrow();

        assertEquals(suggested, found, "sedící přesný roh se neposouvá (resume)");
    }
}
