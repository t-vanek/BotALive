package dev.botalive.core.nether;

import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testy přepočtu souřadnic overworld ↔ Nether (1:8).
 */
class NetherMathTest {

    @Test
    void doNetheruDeliOsmi() {
        BlockPos nether = NetherMath.toNether(new BlockPos(800, 70, 160));
        assertEquals(100, nether.x());
        assertEquals(20, nether.z());
        assertEquals(70, nether.y(), "y v cestovním pásmu se nemění");
    }

    @Test
    void zaporneSouradniceZaokrouhlujiKZapornemuNekonecnu() {
        // floor(−33 / 8) = −5, ne −4 – jinak by kotva mířila o chunk vedle.
        BlockPos nether = NetherMath.toNether(new BlockPos(-33, 64, -1));
        assertEquals(-5, nether.x());
        assertEquals(-1, nether.z());
    }

    @Test
    void yMimoPasmoSeOrizne() {
        assertEquals(NetherMath.NETHER_TRAVEL_MAX_Y,
                NetherMath.toNether(new BlockPos(0, 200, 0)).y(),
                "nad stropem Netheru se cestovat nedá");
        assertEquals(NetherMath.NETHER_TRAVEL_MIN_Y,
                NetherMath.toNether(new BlockPos(0, 5, 0)).y(),
                "pod y=31 je lávový oceán");
    }

    @Test
    void zNetheruNasobiOsmi() {
        BlockPos overworld = NetherMath.toOverworld(new BlockPos(100, 70, -5));
        assertEquals(800, overworld.x());
        assertEquals(-40, overworld.z());
        assertEquals(70, overworld.y());
    }

    @Test
    void tamAZpetDrziOkoliPuvodnihoBodu() {
        BlockPos home = new BlockPos(1234, 68, -777);
        BlockPos roundTrip = NetherMath.toOverworld(NetherMath.toNether(home));
        // Celočíselné dělení ztrácí max. 7 bloků – pořád „u domova".
        assertEquals(0, Math.floorDiv(home.x() - roundTrip.x(), 8));
        assertEquals(0, Math.floorDiv(home.z() - roundTrip.z(), 8));
    }
}
