package dev.botalive.core.physics;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ověřuje hledání útočiště pro nouzový přesun watchdogu: na volném terénu
 * najde sousední buňku, ve zazděné kapse poctivě přizná, že není kam uhnout
 * (nesmí vrátit výchozí pozici – teleport na ni je no-op), a schod o patro
 * výš/níž ještě za útočiště považuje.
 */
class RefugeTest {

    private static final int FLOOR_Y = 64;

    /** Buňka nad podlahou, kde bot stojí. */
    private static BlockPos feet(int x, int z) {
        return new BlockPos(x, FLOOR_Y + 1, z);
    }

    @Test
    void findsNeighbourOnOpenGround() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);

        BlockPos refuge = Refuge.findNear(world, feet(0, 0));

        assertNotEquals(null, refuge);
        assertNotEquals(feet(0, 0), refuge, "útočiště nesmí být výchozí pozice");
        assertTrue(Refuge.standable(world, refuge));
    }

    /**
     * Zazděná kapse 1×1: kolem dokola i o patro výš/níž pevný kámen. Tohle je
     * přesně situace, ve které dřívější kód vracel výchozí pozici a watchdog se
     * teleportoval sám na sebe.
     */
    @Test
    void reportsNoRefugeWhenWalledIn() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        // Pevný masiv v okruhu 3 bloků a ve všech patrech, která se prohledávají
        // (nohy ±1 a nad nimi hlava), kromě samotné buňky bota.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    if (dx == 0 && dz == 0 && dy >= 0 && dy <= 1) {
                        continue; // bot má vlastní buňku a nad hlavou volno
                    }
                    world.set(dx, FLOOR_Y + 1 + dy, dz, FakeWorldView.SOLID);
                }
            }
        }

        assertNull(Refuge.findNear(world, feet(0, 0)),
                "bez volné buňky musí vrátit null, ne výchozí pozici");
    }

    /** Schod: ve vlastní rovině je zeď, ale o blok výš se stojí. */
    @Test
    void findsGroundOneLevelUp() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        // Zeď v celém prstenci r=1..2 ve vlastní rovině nohou i hlavy…
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                world.set(dx, FLOOR_Y + 1, dz, FakeWorldView.SOLID);
                world.set(dx, FLOOR_Y + 2, dz, FakeWorldView.SOLID);
            }
        }
        // …a jedna volná plošina o patro výš (podlaha FLOOR_Y+1 už pevná je).
        world.set(1, FLOOR_Y + 2, 0, FakeWorldView.AIRLIKE);
        world.set(1, FLOOR_Y + 3, 0, FakeWorldView.AIRLIKE);

        assertEquals(new BlockPos(1, FLOOR_Y + 2, 0), Refuge.findNear(world, feet(0, 0)));
    }

    @Test
    void nullWorldYieldsNoRefuge() {
        assertNull(Refuge.findNear(null, feet(0, 0)));
    }
}
