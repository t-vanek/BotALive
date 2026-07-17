package dev.botalive.core.build;

import dev.botalive.core.util.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Plán jednoduchého domku 4×4 – čistá geometrie, jednotkově testovatelná.
 *
 * <p>Domek: obvodové zdi 3 bloky vysoké s dveřním otvorem 1×2 na jižní
 * straně (směr −z od originu je „ven"), plná střecha ve výšce 3. Origin je
 * severozápadní roh na úrovni podlahy (nohou). Interiér 2×2 zůstává volný;
 * bot celý domek postaví z jednoho místa uvnitř a vyjde dveřmi.</p>
 */
public final class HouseBlueprint {

    /** Šířka/hloubka půdorysu (vnější). */
    public static final int SIZE = 4;
    /** Výška zdí. */
    public static final int WALL_HEIGHT = 3;
    /** Sloupec dveří (x offset od originu, z = 0). */
    public static final int DOOR_X = 1;

    private HouseBlueprint() {
    }

    /**
     * Bloky domku v pořadí stavby (zdi zdola nahoru, pak střecha od krajů).
     *
     * @param origin severozápadní roh půdorysu na úrovni podlahy
     * @return pozice bloků k položení
     */
    public static List<BlockPos> placements(BlockPos origin) {
        List<BlockPos> result = new ArrayList<>();
        // Zdi po vrstvách.
        for (int y = 0; y < WALL_HEIGHT; y++) {
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    if (!isPerimeter(x, z)) {
                        continue;
                    }
                    if (isDoor(x, y, z)) {
                        continue;
                    }
                    result.add(origin.offset(x, y, z));
                }
            }
        }
        // Střecha: nejdřív buňky nad zdí (mají oporu), pak vnitřní.
        for (int pass = 0; pass < 2; pass++) {
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    boolean edge = isPerimeter(x, z);
                    if ((pass == 0) == edge) {
                        result.add(origin.offset(x, WALL_HEIGHT, z));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Sloupce, pod kterými musí být pevná zem (celý půdorys).
     *
     * @param origin roh půdorysu
     * @return pozice bloků podlahy (y-1 pod originem)
     */
    public static List<BlockPos> groundColumns(BlockPos origin) {
        List<BlockPos> result = new ArrayList<>();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                result.add(origin.offset(x, -1, z));
            }
        }
        return result;
    }

    /**
     * Prostor, který musí být volný (celý objem domku do výšky střechy).
     *
     * @param origin roh půdorysu
     * @return pozice, kde nesmí zůstat pevný blok
     */
    public static List<BlockPos> clearVolume(BlockPos origin) {
        List<BlockPos> result = new ArrayList<>();
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y <= WALL_HEIGHT; y++) {
                for (int z = 0; z < SIZE; z++) {
                    result.add(origin.offset(x, y, z));
                }
            }
        }
        return result;
    }

    /** @return pozice, kde bot stojí při stavbě (vnitřek domku) */
    public static BlockPos standPoint(BlockPos origin) {
        return origin.offset(2, 0, 2);
    }

    /** @return počet bloků potřebných na celý domek */
    public static int blocksNeeded() {
        return placements(new BlockPos(0, 0, 0)).size();
    }

    private static boolean isPerimeter(int x, int z) {
        return x == 0 || x == SIZE - 1 || z == 0 || z == SIZE - 1;
    }

    private static boolean isDoor(int x, int y, int z) {
        return z == 0 && x == DOOR_X && y <= 1;
    }
}
