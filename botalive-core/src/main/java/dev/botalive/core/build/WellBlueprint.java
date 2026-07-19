package dev.botalive.core.build;

import dev.botalive.core.util.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Studna návsi – první společná stavba sídla (růstová roadmapa, fáze B).
 *
 * <p>Kamenný věnec 3×3 s pochodní na obrubě: čistě běžné stavební materiály
 * (žádné kbelíky s vodou), přesto na návsi čitelný orientační bod. Stejný
 * kontrakt jako {@link HouseBlueprint} – {@code placements/clearVolume/
 * groundColumns/standPoint} – takže stavbu vykonává tatáž mašinérie
 * ({@code MineBlockTask}/{@code PlaceBlockTask}). Symetrická stavba:
 * orientace nemá vliv, stojí se u severní hrany.</p>
 */
public final class WellBlueprint {

    /** Půdorys (bloky). */
    public static final int SIZE = 3;

    private WellBlueprint() {
    }

    /**
     * Bloky věnce (obvod 3×3, střed zůstává volný – šachta studny).
     *
     * @param origin roh půdorysu (SZ, úroveň podlahy)
     * @return pozice v pořadí pokládky
     */
    public static List<BlockPos> placements(BlockPos origin) {
        List<BlockPos> result = new ArrayList<>();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (x == SIZE / 2 && z == SIZE / 2) {
                    continue;
                }
                result.add(origin.offset(x, 0, z));
            }
        }
        return result;
    }

    /**
     * Prostor, který musí být před stavbou volný (3×3, tři bloky výšky).
     *
     * @param origin roh půdorysu
     * @return pozice k vyčištění
     */
    public static List<BlockPos> clearVolume(BlockPos origin) {
        List<BlockPos> result = new ArrayList<>();
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    result.add(origin.offset(x, y, z));
                }
            }
        }
        return result;
    }

    /**
     * Sloupce podlahy pod stavbou (zásyp děr).
     *
     * @param origin roh půdorysu
     * @return pozice o blok pod úrovní stavby
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
     * @param origin roh půdorysu
     * @return místo stavitele – u severní hrany, s dosahem na celý věnec
     */
    public static BlockPos standPoint(BlockPos origin) {
        return origin.offset(SIZE / 2, 0, -1);
    }

    /**
     * @param origin roh půdorysu
     * @return pozice pochodně na SZ rohu věnce
     */
    public static BlockPos torchSpot(BlockPos origin) {
        return origin.offset(0, 1, 0);
    }

    /** @return kolik stavebních bloků věnec spotřebuje */
    public static int blocksNeeded() {
        return SIZE * SIZE - 1;
    }
}
