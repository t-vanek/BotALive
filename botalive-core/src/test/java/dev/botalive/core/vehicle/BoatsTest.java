package dev.botalive.core.vehicle;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy čistých pomůcek kolem lodí – rozpoznání lodi a měření šířky vody.
 *
 * <p>Klíčové je, aby se loď vzala jen na dost širokou souvislou vodu ve směru
 * cíle (kratší kaluž bot přeplave) a aby detekce vody chytila i hladinu, po
 * které loď reálně pluje (voda o blok níž než nohy).</p>
 */
class BoatsTest {

    private static final int FLOOR_Y = 62;
    private static final int FEET_Y = FLOOR_Y + 1;

    /** Jezero: voda na hladině FLOOR_Y v pruhu x[fromX..toX], jinak pevnina. */
    private static FakeWorldView lake(int fromX, int toX) {
        FakeWorldView world = new FakeWorldView(FLOOR_Y);
        for (int x = fromX; x <= toX; x++) {
            for (int z = -4; z <= 4; z++) {
                world.set(x, FLOOR_Y, z, FakeWorldView.WATER);
            }
        }
        return world;
    }

    @Test
    void zmeriSouvislouSirkuVody() {
        FakeWorldView world = lake(3, 14); // 12 vodních sloupců
        BlockPos feet = new BlockPos(0, FEET_Y, 0);
        assertEquals(12, Boats.openWaterWidth(world, feet, 1, 0), "šířka souvislé vody na +X");
    }

    @Test
    void tolerujeParBlokuBrehuNezZacneVoda() {
        // Voda začíná až 2 bloky před botem – pořád se má naměřit.
        FakeWorldView world = lake(2, 11); // 10 sloupců, začíná na x2
        BlockPos feet = new BlockPos(0, FEET_Y, 0);
        assertEquals(10, Boats.openWaterWidth(world, feet, 1, 0));
    }

    @Test
    void zastaviSeNaDruhemBrehu() {
        // Za vodou je pevnina i další jezero – měří se jen první souvislý úsek.
        FakeWorldView world = lake(1, 6);
        for (int x = 10; x <= 20; x++) {
            for (int z = -4; z <= 4; z++) {
                world.set(x, FLOOR_Y, z, FakeWorldView.WATER);
            }
        }
        BlockPos feet = new BlockPos(0, FEET_Y, 0);
        assertEquals(6, Boats.openWaterWidth(world, feet, 1, 0), "za břehem se neměří dál");
    }

    @Test
    void bezVodyVeSmeruVratiNulu() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y); // samá pevnina
        BlockPos feet = new BlockPos(0, FEET_Y, 0);
        assertEquals(0, Boats.openWaterWidth(world, feet, 1, 0));
    }

    @Test
    void sirokaVodaPrekonavaPrahLodi() {
        BlockPos feet = new BlockPos(0, FEET_Y, 0);
        assertTrue(Boats.openWaterWidth(lake(1, 12), feet, 1, 0) >= Boats.MIN_CROSS_WIDTH,
                "12 bloků vody se vyplatí přeplout lodí");
        assertTrue(Boats.openWaterWidth(lake(1, 4), feet, 1, 0) < Boats.MIN_CROSS_WIDTH,
                "krátkou kaluž bot přeplave sám");
    }

    @Test
    void hladinaJeSplavnaIZUrovneNohou() {
        // Nohy o blok nad hladinou – loď pluje po vodě pod nimi.
        FakeWorldView world = lake(0, 4);
        assertTrue(Boats.isWaterColumn(world, new BlockPos(0, FEET_Y, 0)), "voda o blok níž");
        assertTrue(Boats.isWaterColumn(world, new BlockPos(0, FLOOR_Y, 0)), "voda přímo v pozici");
    }

    @Test
    void lavaNiSouseNejsouSplavne() {
        FakeWorldView world = new FakeWorldView(FLOOR_Y).set(0, FLOOR_Y, 0, FakeWorldView.HAZARD);
        assertFalse(Boats.isWaterColumn(world, new BlockPos(0, FLOOR_Y, 0)), "láva není splavná");
        assertFalse(Boats.isWaterColumn(world, new BlockPos(5, FEET_Y, 5)), "souš není splavná");
    }

    @Test
    void poznaLodniItemAEntitu() {
        assertTrue(Boats.isBoatItem(Material.OAK_BOAT));
        assertTrue(Boats.isBoatItem(Material.BAMBOO_RAFT));
        assertFalse(Boats.isBoatItem(Material.STONE));
        assertFalse(Boats.isBoatItem(null));

        assertTrue(Boats.isBoatType("OAK_BOAT"));
        assertTrue(Boats.isBoatType("BAMBOO_RAFT"));
        assertTrue(Boats.isBoatType("OAK_CHEST_BOAT"));
        assertFalse(Boats.isBoatType("MINECART"));
    }

    @Test
    void najdeNejblizsiHladinuProPolozeniLodi() {
        FakeWorldView world = lake(3, 6);
        BlockPos found = Boats.nearestWater(world, new BlockPos(0, FEET_Y, 0), 4);
        assertEquals(new BlockPos(3, FLOOR_Y, 0), found, "nejbližší hladina se vzduchem nad ní");
    }
}
