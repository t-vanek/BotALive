package dev.botalive.core.ai.goals;

import dev.botalive.core.build.Enclosure;
import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy geometrie hradeb – obvod po vnějším obsazeném prstenci parcel a brány
 * na čtyřech osách (kudy vyjíždějí cesty). Stavbu vede {@link BarrierWorker}
 * (testuje se v provozu jako {@link DecorWorker}); tady jde o čistý plán.
 */
class SettlementWallGoalTest {

    private static final int Y = 64;
    private static final BlockPos CENTER = new BlockPos(0, Y, 0);
    private static final int SPACING = 12;

    @Test
    void hradbaObkrouziVnejsiPrstenec() {
        // Jedna parcela na prstenci 1 (východ) → ext = 1·12 + 12/2 = 18. Rezerva
        // je půl rozestupu (footprint-nezávislá), ať hradba mine i širší domy.
        List<BlockPos> plots = List.of(new BlockPos(10, Y, -2));
        SettlementWallGoal.WallBounds b = SettlementWallGoal.wallBounds(CENTER, plots, SPACING);
        assertEquals(new BlockPos(-18, Y, -18), b.min());
        assertEquals(new BlockPos(18, Y, 18), b.max());
    }

    @Test
    void hradbaRosteSNejvzdalenejsimPrstencem() {
        // Prsten 1 i 2 obsazený → počítá se ten vnější (ext = 2·12 + 12/2 = 30).
        List<BlockPos> plots = List.of(new BlockPos(10, Y, -2), new BlockPos(22, Y, -2));
        SettlementWallGoal.WallBounds b = SettlementWallGoal.wallBounds(CENTER, plots, SPACING);
        assertEquals(new BlockPos(-30, Y, -30), b.min());
        assertEquals(new BlockPos(30, Y, 30), b.max());
    }

    @Test
    void odvozeniPrstenceNezavisiNaVelikostiDomu() {
        // Roh 9-širokého domu na prstenci 1 (východ) je uzel (12,0,0) − 4 = (8,0,-4);
        // musí dát tentýž prstenec (a hradbu) jako domek 4×4 (roh (10,0,-2)).
        List<BlockPos> wide = List.of(new BlockPos(8, Y, -4));
        SettlementWallGoal.WallBounds b = SettlementWallGoal.wallBounds(CENTER, wide, SPACING);
        assertEquals(new BlockPos(-18, Y, -18), b.min());
        assertEquals(new BlockPos(18, Y, 18), b.max());
    }

    @Test
    void bezObsazenehoPrstenceZadnaHradba() {
        assertNull(SettlementWallGoal.wallBounds(CENTER, List.of(), SPACING));
        assertNull(SettlementWallGoal.wallBounds(CENTER, Arrays.asList((BlockPos) null), SPACING));
        // Parcela na návsi (prsten 0) – hradbu kolem jednoho domu nestavíme.
        assertNull(SettlementWallGoal.wallBounds(CENTER,
                List.of(new BlockPos(-2, Y, -2)), SPACING));
    }

    @Test
    void branyJsouNaCtyrechOsach() {
        SettlementWallGoal.WallBounds b = SettlementWallGoal.wallBounds(CENTER,
                List.of(new BlockPos(10, Y, -2)), SPACING);
        List<Enclosure.Post> posts = Enclosure.plan(new FakeWorldView(Y), b.min(), b.max(), Y,
                Set.of(Cardinal.NORTH, Cardinal.SOUTH, Cardinal.EAST, Cardinal.WEST), 1000);
        List<BlockPos> gates = posts.stream().filter(Enclosure.Post::gate)
                .map(Enclosure.Post::base).toList();
        assertEquals(4, gates.size(), "brána na každé ose");
        // Střed hrany (min/max = ±18 → střed 0); plot stojí na zemi (Y+1).
        assertTrue(gates.contains(new BlockPos(0, Y + 1, -18)), "severní brána");
        assertTrue(gates.contains(new BlockPos(0, Y + 1, 18)), "jižní brána");
        assertTrue(gates.contains(new BlockPos(-18, Y + 1, 0)), "západní brána");
        assertTrue(gates.contains(new BlockPos(18, Y + 1, 0)), "východní brána");
    }
}
