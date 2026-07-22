package dev.botalive.core.ai.goals;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometrie stanoviště stráže ({@link BuildGuardGoal#guardPostColumn}). Tick
 * smyčka (navigace, rozhlížení, přebití bojem) se ověřuje jen naživo; sloupec
 * stanoviště je čistá funkce a chytá regrese směru a odstupu od stavby.
 */
class BuildGuardGoalTest {

    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

    @Test
    void stanovisteStojiPredStavbouVeSmeruPristupu() {
        // Dveře k severu → stráž stojí severně od středu půdorysu.
        BlockPos north = BuildGuardGoal.guardPostColumn(ORIGIN, Cardinal.NORTH);
        assertEquals(2, north.x(), "x = střed 5×5 (origin+2)");
        assertTrue(north.z() < 2, "stanoviště je před stavbou (na sever od středu)");

        BlockPos south = BuildGuardGoal.guardPostColumn(ORIGIN, Cardinal.SOUTH);
        assertTrue(south.z() > 2, "opačná orientace → opačná strana");
    }

    @Test
    void stanovisteJeMimoPudorysStavby() {
        // 3 bloky ve směru facing od středu (origin+2) → 5 od originu, mimo
        // libovolný rozumný půdorys (studna 3, radnice 5, kostel 5×7).
        for (Cardinal facing : Cardinal.values()) {
            BlockPos post = BuildGuardGoal.guardPostColumn(ORIGIN, facing);
            int offX = Math.abs(post.x() - (ORIGIN.x() + 2));
            int offZ = Math.abs(post.z() - (ORIGIN.z() + 2));
            assertEquals(3, offX + offZ, "stráž stojí 3 bloky od středu ve směru přístupu");
        }
    }

    @Test
    void vyskaSedNaUrovniOriginu() {
        assertEquals(ORIGIN.y(),
                BuildGuardGoal.guardPostColumn(ORIGIN, Cardinal.EAST).y(),
                "sloupec drží y originu; skutečnou výšku dohledá guardPost");
    }
}
