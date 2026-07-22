package dev.botalive.core.ai.goals;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy hledání zdrojů materiálu pro opravu (čistý sken okolí). Samotné
 * shánění (chůze, těžba) běží v provozu jako {@link BarrierWorker}.
 */
class BarrierGatherTest {

    private static final int Y = 65;
    private static final Predicate<Material> LOGS = m -> m.name().endsWith("_LOG");

    @Test
    void najdeNejblizsiOdkrytouKladu() {
        FakeWorldView world = new FakeWorldView(64);
        world.set(3, Y, 0, Material.OAK_LOG, FakeWorldView.SOLID);   // odkrytá (vzduch kolem)
        world.set(7, Y, 0, Material.SPRUCE_LOG, FakeWorldView.SOLID); // dál
        List<BlockPos> found = BarrierGather.scanSources(world, new BlockPos(0, Y, 0), LOGS, 10);
        assertEquals(2, found.size());
        assertEquals(new BlockPos(3, Y, 0), found.get(0), "nejbližší první");
    }

    @Test
    void zasypanaKladaSeNekope() {
        FakeWorldView world = new FakeWorldView(64);
        // Kláda pod povrchem se solidními sousedy (y≤64) – nemá průchozího souseda.
        world.set(2, 63, 0, Material.OAK_LOG, FakeWorldView.SOLID);
        assertTrue(BarrierGather.scanSources(world, new BlockPos(0, Y, 0), LOGS, 10).isEmpty());
    }

    @Test
    void bezZdrojePrazdno() {
        assertTrue(BarrierGather.scanSources(new FakeWorldView(64), new BlockPos(0, Y, 0), LOGS, 10)
                .isEmpty());
    }
}
