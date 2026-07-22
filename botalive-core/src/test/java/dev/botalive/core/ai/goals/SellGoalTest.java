package dev.botalive.core.ai.goals;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test kotvy prodejní nabídky k tržišti. Prodávající člen města vyvěšuje
 * nabídku od pultu (ne kde zrovna stojí) – geometrii pultu drží
 * {@link dev.botalive.core.build.plan.Blueprints#marketStall()}, samotný
 * dojezd/vyvěšení řeší {@code SellGoal} v provozu (fáze GO → OFFER).
 */
class SellGoalTest {

    @Test
    void kotvaNabidkyJeStredPultuTrziste() {
        BlockPos origin = new BlockPos(10, 64, 20);
        // Tržiště 3×3: prodejce se staví do středu pultu pod stříškou
        // (origin + (1,0,1)).
        assertEquals(new BlockPos(11, 64, 21),
                SellGoal.stallAnchor(origin, Cardinal.NORTH),
                "prodejce se staví do středu pultu");
    }

    @Test
    void kotvaNezavisiNaOrientaciSymetrickehoPultu() {
        BlockPos origin = new BlockPos(0, 70, 0);
        BlockPos expected = new BlockPos(1, 70, 1);
        for (Cardinal facing : Cardinal.values()) {
            assertEquals(expected, SellGoal.stallAnchor(origin, facing),
                    "symetrický pult 3×3 má stání ve středu bez ohledu na orientaci");
        }
    }
}
