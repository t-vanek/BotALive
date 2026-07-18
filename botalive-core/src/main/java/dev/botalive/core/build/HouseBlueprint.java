package dev.botalive.core.build;

import dev.botalive.core.util.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Plán jednoduchého domku 4×4 – čistá geometrie, jednotkově testovatelná.
 *
 * <p>Domek: obvodové zdi 3 bloky vysoké s dveřním otvorem 1×2, plná střecha
 * ve výšce 3. Origin je roh půdorysu s minimálními souřadnicemi na úrovni
 * podlahy (nohou). Interiér 2×2 zůstává volný; bot celý domek postaví
 * z jednoho místa uvnitř a vyjde dveřmi.</p>
 *
 * <p>Domek se umí natočit ({@link HouseFacing}) – dveře vždy míří na stranu
 * dané orientace, takže domy ve vesnici koukají na náves. {@link HouseFacing#NORTH}
 * odpovídá původní geometrii (dveřní otvor na hraně z = 0).</p>
 */
public final class HouseBlueprint {

    /** Šířka/hloubka půdorysu (vnější). */
    public static final int SIZE = 4;
    /** Výška zdí. */
    public static final int WALL_HEIGHT = 3;
    /** Sloupec dveří (x offset od originu, z = 0, před natočením). */
    public static final int DOOR_X = 1;

    private HouseBlueprint() {
    }

    /**
     * Bloky domku v pořadí stavby (zdi zdola nahoru, pak střecha od krajů).
     *
     * @param origin roh půdorysu s minimálními souřadnicemi na úrovni podlahy
     * @param facing orientace dveří
     * @return pozice bloků k položení
     */
    public static List<BlockPos> placements(BlockPos origin, HouseFacing facing) {
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
                    result.add(local(origin, x, y, z, facing));
                }
            }
        }
        // Střecha: nejdřív buňky nad zdí (mají oporu), pak vnitřní.
        for (int pass = 0; pass < 2; pass++) {
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    boolean edge = isPerimeter(x, z);
                    if ((pass == 0) == edge) {
                        result.add(local(origin, x, WALL_HEIGHT, z, facing));
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

    /**
     * @param origin roh půdorysu
     * @param facing orientace dveří
     * @return pozice, kde bot stojí při stavbě (vnitřek domku)
     */
    public static BlockPos standPoint(BlockPos origin, HouseFacing facing) {
        return local(origin, 2, 0, 2, facing);
    }

    /**
     * @param origin roh půdorysu
     * @param facing orientace dveří
     * @return spodní blok dveřního otvoru
     */
    public static BlockPos doorBottom(BlockPos origin, HouseFacing facing) {
        return local(origin, DOOR_X, 0, 0, facing);
    }

    /**
     * @param origin roh půdorysu
     * @param facing orientace dveří
     * @return vnitřní pozice pro pochodeň
     */
    public static BlockPos torchSpot(BlockPos origin, HouseFacing facing) {
        return local(origin, 2, 0, 1, facing);
    }

    /**
     * @param origin roh půdorysu
     * @param facing orientace dveří
     * @return vnitřní pozice pro postel
     */
    public static BlockPos bedSpot(BlockPos origin, HouseFacing facing) {
        return local(origin, 1, 0, 1, facing);
    }

    /** Počet bloků domku – konstanta, počítá se jednou (čte se v utility 4×/s). */
    private static final int BLOCKS_NEEDED =
            placements(new BlockPos(0, 0, 0), HouseFacing.NORTH).size();

    /** @return počet bloků potřebných na celý domek */
    public static int blocksNeeded() {
        return BLOCKS_NEEDED;
    }

    /**
     * Převod lokální souřadnice půdorysu na světovou podle orientace.
     * Půdorys je čtverec, takže natočení jen přemapuje (x, z) uvnitř
     * stejného objemu – {@code origin} zůstává minimálním rohem.
     */
    private static BlockPos local(BlockPos origin, int x, int y, int z, HouseFacing facing) {
        int wx;
        int wz;
        switch (facing) {
            case NORTH -> {
                wx = x;
                wz = z;
            }
            case SOUTH -> {
                wx = SIZE - 1 - x;
                wz = SIZE - 1 - z;
            }
            case WEST -> {
                wx = z;
                wz = x;
            }
            case EAST -> {
                wx = SIZE - 1 - z;
                wz = x;
            }
            default -> throw new IllegalStateException();
        }
        return origin.offset(wx, y, wz);
    }

    private static boolean isPerimeter(int x, int z) {
        return x == 0 || x == SIZE - 1 || z == 0 || z == SIZE - 1;
    }

    private static boolean isDoor(int x, int y, int z) {
        return z == 0 && x == DOOR_X && y <= 1;
    }
}
