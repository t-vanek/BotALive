package dev.botalive.core.nether;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy geometrie oltáře witheru – stejné invarianty jako u portálového
 * rámu: každý blok pokládky má oporu a prostřední lebka jde poslední.
 */
class WitherAltarTest {

    private static final int FLOOR = 40;

    @Test
    void kazdyBlokPokladkyMaOporu() {
        for (boolean axisX : new boolean[]{true, false}) {
            BlockPos base = new BlockPos(10, FLOOR + 1, 10);
            Set<Long> placed = new HashSet<>();
            for (BlockPos pos : WitherAltar.sandPlacements(base, axisX)) {
                boolean onGround = pos.y() == base.y(); // pata stojí na zemi
                boolean onPlaced = placed.contains(pos.down().asLong())
                        || neighborPlaced(placed, pos);
                assertTrue(onGround || onPlaced,
                        "blok " + pos + " nemá oporu (axisX=" + axisX + ")");
                placed.add(pos.asLong());
            }
            assertEquals(WitherAltar.SOUL_SAND_NEEDED,
                    WitherAltar.sandPlacements(base, axisX).size());
        }
    }

    private static boolean neighborPlaced(Set<Long> placed, BlockPos pos) {
        return placed.contains(pos.offset(1, 0, 0).asLong())
                || placed.contains(pos.offset(-1, 0, 0).asLong())
                || placed.contains(pos.offset(0, 0, 1).asLong())
                || placed.contains(pos.offset(0, 0, -1).asLong());
    }

    @Test
    void prostredniLebkaJdePosledni() {
        BlockPos base = new BlockPos(0, FLOOR + 1, 0);
        for (boolean axisX : new boolean[]{true, false}) {
            List<BlockPos> skulls = WitherAltar.skullSupports(base, axisX);
            assertEquals(WitherAltar.SKULLS_NEEDED, skulls.size());
            // Poslední podpora lebky je tělo (střed) – dokončení budí bosse.
            assertEquals(base.up(), skulls.get(skulls.size() - 1),
                    "prostřední lebka musí jít poslední (axisX=" + axisX + ")");
            // Lebky sedí na blocích pokládky.
            Set<Long> sand = new HashSet<>();
            WitherAltar.sandPlacements(base, axisX)
                    .forEach(p -> sand.add(p.asLong()));
            for (BlockPos support : skulls) {
                assertTrue(sand.contains(support.asLong()),
                        "podpora lebky " + support + " není soul sand");
            }
        }
    }

    @Test
    void staveniteNaRovineSeNajdeALavoveOdmita() {
        FakeWorldView flat = new FakeWorldView(FLOOR);
        BlockPos site = WitherAltar.findBuildSite(flat, new BlockPos(0, FLOOR + 1, 0), 16);
        assertNotNull(site, "rovina má staveniště nabídnout");
        assertTrue(WitherAltar.siteUsable(flat, site));

        // Lávové jezero pod nohama staveniště nesmí projít.
        FakeWorldView lava = new FakeWorldView(FLOOR);
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                lava.set(x, FLOOR, z, FakeWorldView.HAZARD);
            }
        }
        assertNull(WitherAltar.findBuildSite(lava, new BlockPos(0, FLOOR + 1, 0), 12),
                "nad lávou se oltář nestaví");
    }

    @Test
    void stanovisteJeMimoOltarAleNaDosahTela() {
        BlockPos base = new BlockPos(5, FLOOR + 1, 5);
        for (boolean axisX : new boolean[]{true, false}) {
            BlockPos stand = WitherAltar.standPoint(base, axisX);
            Set<Long> altar = new HashSet<>();
            WitherAltar.sandPlacements(base, axisX)
                    .forEach(p -> altar.add(p.asLong()));
            assertFalse(altar.contains(stand.asLong()), "stavitel nestojí v oltáři");
            // Horní plocha těla (poslední lebka) musí být na dosah ruky (~4,5).
            double reach = stand.center().add(0, 1.62, 0)
                    .distance(base.up().center().add(0, 0.5, 0));
            assertTrue(reach <= 4.5, "poslední lebka na dosah, reach=" + reach);
        }
    }
}
