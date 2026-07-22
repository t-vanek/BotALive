package dev.botalive.core.ai.goals;

import dev.botalive.core.build.Enclosure;
import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy geometrie plotu kolem domu – obdélník s odsazením (dvorek) a branka na
 * straně dveří. Samotné stavění (chůze, equip) vede {@link BarrierWorker} a
 * testuje se v provozu (jako {@link DecorWorker}); tady jde o čistý plán.
 */
class SettlementFenceGoalTest {

    private static final int Y = 64;
    private static final BlockPos ORIGIN = new BlockPos(0, Y, 0);

    @Test
    void plotObkrouziDumSDvorkem() {
        // Domek 4×4 (origin 0..3) → plot 6×6 s odsazením 1 (min −1, max 4).
        SettlementFenceGoal.FenceBounds b = SettlementFenceGoal.fenceBounds(ORIGIN, Cardinal.NORTH);
        assertEquals(new BlockPos(-1, Y, -1), b.min());
        assertEquals(new BlockPos(4, Y, 4), b.max());
        assertEquals(Cardinal.NORTH, b.gate());
    }

    @Test
    void brankaJeVzdyNaStraneDveri() {
        for (Cardinal facing : Cardinal.values()) {
            assertEquals(facing, SettlementFenceGoal.fenceBounds(ORIGIN, facing).gate(),
                    "branka na straně dveří: " + facing);
        }
    }

    @Test
    void planDaObvod6x6SBrankouNaSever() {
        SettlementFenceGoal.FenceBounds b = SettlementFenceGoal.fenceBounds(ORIGIN, Cardinal.NORTH);
        List<Enclosure.Post> posts = Enclosure.plan(new FakeWorldView(Y), b.min(), b.max(), Y,
                Set.of(b.gate()), 100);
        assertEquals(20, posts.size(), "obvod 6×6 = 2·6 + 2·6 − 4");
        List<Enclosure.Post> gates = posts.stream().filter(Enclosure.Post::gate).toList();
        assertEquals(1, gates.size());
        // Severní hrana z = minZ = −1, střed x = (−1+4)/2 = 1; plot stojí na zemi (Y+1).
        assertEquals(new BlockPos(1, Y + 1, -1), gates.get(0).base());
    }

    @Test
    void plotStojiNaZemiPoObvodu() {
        SettlementFenceGoal.FenceBounds b = SettlementFenceGoal.fenceBounds(ORIGIN, Cardinal.EAST);
        List<Enclosure.Post> posts = Enclosure.plan(new FakeWorldView(Y), b.min(), b.max(), Y,
                Set.of(b.gate()), 100);
        for (Enclosure.Post p : posts) {
            int x = p.base().x();
            int z = p.base().z();
            assertTrue(x == -1 || x == 4 || z == -1 || z == 4, "jen obvod: " + p.base());
            assertEquals(Y + 1, p.base().y());
        }
    }
}
