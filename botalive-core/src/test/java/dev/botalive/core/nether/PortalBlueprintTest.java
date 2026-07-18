package dev.botalive.core.nether;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy geometrie nether portálu – rám, pořadí stavby, validace místa.
 */
class PortalBlueprintTest {

    private static final int FLOOR = 63;
    /** Vnitřní spodní roh: 2 nad horní hranou země (spodní řada leží na zemi). */
    private static final BlockPos BASE = new BlockPos(0, FLOOR + 2, 0);

    /** Pevný blok s hazardem (magma) – na testy nebezpečného podloží. */
    private static final BlockTraits SOLID_HAZARD = BlockTraits.simple(
            false, true, false, false, false, true, false, false, false);

    @Test
    void ramMa14BlokuAVnitrek6() {
        assertEquals(14, PortalBlueprint.framePlacements(BASE, true).size());
        assertEquals(PortalBlueprint.OBSIDIAN_NEEDED,
                PortalBlueprint.framePlacements(BASE, false).size());
        assertEquals(6, PortalBlueprint.interior(BASE, true).size());
        // Rám a vnitřek se nesmí překrývat.
        Set<BlockPos> frame = new HashSet<>(PortalBlueprint.framePlacements(BASE, true));
        for (BlockPos cell : PortalBlueprint.interior(BASE, true)) {
            assertFalse(frame.contains(cell), "vnitřek nesmí být součástí rámu: " + cell);
        }
    }

    @Test
    void poradiStavbyMaVzdyOporu() {
        for (boolean axisX : new boolean[]{true, false}) {
            Set<BlockPos> placed = new HashSet<>();
            for (BlockPos next : PortalBlueprint.framePlacements(BASE, axisX)) {
                assertTrue(hasSupport(next, placed),
                        "blok " + next + " (osa " + (axisX ? "X" : "Z")
                                + ") nemá při pokládce žádného pevného souseda");
                placed.add(next);
            }
        }
    }

    /** Opora: země pod blokem, nebo dříve položený soused (6-okolí). */
    private static boolean hasSupport(BlockPos pos, Set<BlockPos> placed) {
        if (pos.y() - 1 <= FLOOR) {
            return true; // sedí na zemi
        }
        return placed.contains(pos.down()) || placed.contains(pos.up())
                || placed.contains(pos.offset(1, 0, 0)) || placed.contains(pos.offset(-1, 0, 0))
                || placed.contains(pos.offset(0, 0, 1)) || placed.contains(pos.offset(0, 0, -1));
    }

    @Test
    void validaceRamuAZapaleni() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (BlockPos pos : PortalBlueprint.framePlacements(BASE, true)) {
            world.set(pos.x(), pos.y(), pos.z(), Material.OBSIDIAN, FakeWorldView.SOLID);
        }
        assertTrue(PortalBlueprint.isFrame(world, BASE, true));
        assertFalse(PortalBlueprint.isFrame(world, BASE, false),
                "kolmá orientace nemá rám");
        assertTrue(PortalBlueprint.interiorClear(world, BASE, true));
        assertFalse(PortalBlueprint.isLit(world, BASE, true), "nezapálený rám");

        for (BlockPos cell : PortalBlueprint.interior(BASE, true)) {
            world.set(cell.x(), cell.y(), cell.z(), Material.NETHER_PORTAL,
                    FakeWorldView.PORTAL);
        }
        assertTrue(PortalBlueprint.isLit(world, BASE, true), "zapálený portál");
        assertTrue(PortalBlueprint.interiorClear(world, BASE, true),
                "portálové bloky zapálení nevadí");
    }

    @Test
    void zastavenyVnitrekBlokujeZapaleni() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (BlockPos pos : PortalBlueprint.framePlacements(BASE, true)) {
            world.set(pos.x(), pos.y(), pos.z(), Material.OBSIDIAN, FakeWorldView.SOLID);
        }
        world.set(BASE.x(), BASE.y(), BASE.z(), FakeWorldView.SOLID); // kámen uvnitř
        assertFalse(PortalBlueprint.interiorClear(world, BASE, true));
    }

    @Test
    void najdeStavebniMistoNaRovine() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BlockPos site = PortalBlueprint.findBuildSite(world, new BlockPos(0, FLOOR + 1, 0), 16);
        assertNotNull(site, "rovina má místo pro portál");
        assertEquals(FLOOR + 2, site.y(), "base je 2 nad horní hranou země");
        assertTrue(PortalBlueprint.siteUsable(world, site, true)
                || PortalBlueprint.siteUsable(world, site, false));
    }

    @Test
    void mistoNadLavouNeboMagmouNeprojde() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Magma pod celou plochou kandidáta – hazardní podloží se odmítá.
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                world.set(x, FLOOR, z, SOLID_HAZARD);
            }
        }
        assertFalse(PortalBlueprint.siteUsable(world, BASE, true));
        assertFalse(PortalBlueprint.siteUsable(world, BASE, false));
    }

    @Test
    void zapalovaciBlokJePodVstupem() {
        assertEquals(BASE.down(), PortalBlueprint.igniteSupport(BASE));
        assertEquals(BASE, PortalBlueprint.entryCell(BASE));
    }

    @Test
    void stavitelStojiVedleRamuNaZemi() {
        BlockPos standX = PortalBlueprint.standPoint(BASE, true);
        assertEquals(FLOOR + 1, standX.y(), "stavitel stojí na zemi");
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Místo stavitele nesmí kolidovat s rámem.
        for (BlockPos pos : PortalBlueprint.framePlacements(BASE, true)) {
            assertFalse(pos.equals(standX), "stavitel by stál v rámu");
        }
        assertTrue(world.traitsAt(standX.down()).solid());
    }
}
