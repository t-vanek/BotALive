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
}
