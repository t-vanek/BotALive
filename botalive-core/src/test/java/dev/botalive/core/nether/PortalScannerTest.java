package dev.botalive.core.nether;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy hledání portálů – aktivní portály i nezapálené rámy.
 */
class PortalScannerTest {

    private static final int FLOOR = 63;
    private static final BlockPos BASE = new BlockPos(4, FLOOR + 2, -3);

    private static FakeWorldView worldWithFrame(boolean axisX, boolean lit) {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (BlockPos pos : PortalBlueprint.framePlacements(BASE, axisX)) {
            world.set(pos.x(), pos.y(), pos.z(), Material.OBSIDIAN, FakeWorldView.SOLID);
        }
        if (lit) {
            for (BlockPos cell : PortalBlueprint.interior(BASE, axisX)) {
                world.set(cell.x(), cell.y(), cell.z(), Material.NETHER_PORTAL,
                        FakeWorldView.PORTAL);
            }
        }
        return world;
    }

    @Test
    void najdeAktivniPortal() {
        FakeWorldView world = worldWithFrame(true, true);
        Optional<BlockPos> entry = PortalScanner.findActivePortal(
                world, new BlockPos(0, FLOOR + 1, 0), 16, 8);
        assertTrue(entry.isPresent());
        // Vstup je spodní patro vnitřku (pod ním už portál není).
        assertEquals(BASE.y(), entry.get().y());
        assertEquals(Material.NETHER_PORTAL, world.materialAt(entry.get()));
        assertFalse(world.materialAt(entry.get().down()) == Material.NETHER_PORTAL);
    }

    @Test
    void bezPortaluNicNenajde() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        assertTrue(PortalScanner.findActivePortal(
                world, new BlockPos(0, FLOOR + 1, 0), 16, 8).isEmpty());
        assertTrue(PortalScanner.findFrame(
                world, new BlockPos(0, FLOOR + 1, 0), 16, 8).isEmpty());
    }

    @Test
    void najdeNezapalenyRamVObouOrientacich() {
        for (boolean axisX : new boolean[]{true, false}) {
            FakeWorldView world = worldWithFrame(axisX, false);
            Optional<PortalScanner.Frame> frame = PortalScanner.findFrame(
                    world, new BlockPos(0, FLOOR + 1, 0), 16, 8);
            assertTrue(frame.isPresent(), "rám (osa " + (axisX ? "X" : "Z") + ")");
            assertEquals(BASE, frame.get().base());
            assertEquals(axisX, frame.get().axisX());
            assertFalse(frame.get().lit());
            assertEquals(BASE, frame.get().entry());
        }
    }

    @Test
    void zapalenyRamHlasiLit() {
        FakeWorldView world = worldWithFrame(true, true);
        Optional<PortalScanner.Frame> frame = PortalScanner.findFrame(
                world, new BlockPos(0, FLOOR + 1, 0), 16, 8);
        assertTrue(frame.isPresent());
        assertTrue(frame.get().lit());
    }

    @Test
    void osamoceneObsidianyNejsouRam() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(2, FLOOR + 1, 2, Material.OBSIDIAN, FakeWorldView.SOLID);
        world.set(5, FLOOR + 1, 4, Material.OBSIDIAN, FakeWorldView.SOLID);
        assertTrue(PortalScanner.findFrame(
                world, new BlockPos(0, FLOOR + 1, 0), 16, 8).isEmpty());
    }
}
